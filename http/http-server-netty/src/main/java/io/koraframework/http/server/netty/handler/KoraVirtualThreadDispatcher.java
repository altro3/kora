package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.common.telemetry.OpentelemetryContext;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.netty.NettyContext;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.RequestContainer;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.ResponseContainer;
import io.koraframework.logging.common.MDC;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static io.koraframework.http.server.netty.handler.NettyTelemetryHandler.OBSERVATION_KEY;

@ChannelHandler.Sharable
public final class KoraVirtualThreadDispatcher extends ChannelInboundHandlerAdapter {

    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final boolean contextPropagationEnabled;
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Phaser phaser = new Phaser(1);
    private volatile boolean shuttingDown = false;

    public KoraVirtualThreadDispatcher(ValueOf<HttpServerConfig> httpServerConfig, boolean contextPropagationEnabled) {
        this.httpServerConfig = httpServerConfig;
        this.contextPropagationEnabled = contextPropagationEnabled;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof NettyTelemetryHandler.RequestContainer container)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (shuttingDown) {
            var nettyReq = container.nettyReq();
            sendStaticErrorResponse(ctx, nettyReq.protocolVersion(), false, container.telemetryContext(), new IllegalStateException("Server is shutting down"));
            return;
        }

        ctx.channel().config().setAutoRead(false);

        activeRequests.incrementAndGet();
        phaser.register();
        container.nettyReq().retain();

        Thread.startVirtualThread(() -> {
            try {
                dispatch(ctx, container);
            } finally {
                ReferenceCountUtil.release(container.nettyReq());
                activeRequests.decrementAndGet();
                phaser.arriveAndDeregister();
            }
        });
    }

    private void dispatch(ChannelHandlerContext ctx, RequestContainer container) {
        var nettyReq = container.nettyReq();
        var rootCtx = container.telemetryContext();
        var invocation = container.invocation();
        var httpServerRequest = container.httpServerRequest();
        var observation = container.observation();

        ScopedValue.where(NettyContext.VALUE, new NettyContext(nettyReq, ctx))
            .where(MDC.VALUE, new MDC())
            .where(OpentelemetryContext.VALUE, rootCtx)
            .run(() -> {
                HttpServerResponse response;
                try {
                    response = invocation.proceed(httpServerRequest);
                    if (observation != null) {
                        observation.observeResponse(response);
                    }
                } catch (Throwable e) {
                    if (e instanceof HttpServerResponse rs) {
                        response = rs;
                        if (observation != null) {
                            observation.observeError(e);
                            observation.observeResponse(response);
                        }
                    } else {
                        if (observation != null) {
                            var errorResponse = io.koraframework.http.server.common.response.HttpServerResponse.of(500);
                            observation.observeError(e);
                            observation.observeResponse(errorResponse);
                            if (!ctx.channel().isActive()) {
                                observation.end();
                                ctx.channel().attr(io.netty.util.AttributeKey.valueOf("kora.http.observation")).set(null);
                            }
                        }
                        sendStaticErrorResponse(ctx, nettyReq.protocolVersion(), HttpUtil.isKeepAlive(nettyReq), rootCtx, e);
                        return;
                    }
                }
                var responseContainer = new ResponseContainer(nettyReq, response, observation);
                ctx.executor().execute(() -> ctx.fireChannelRead(responseContainer));
            });
    }

    private void sendStaticErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Context context, Throwable t) {
        var message = Objects.requireNonNullElse(t.getMessage(), "Unknown error");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        ByteBuf buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
        errorResp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

        setupConnectionHeader(errorResp, version, keepAlive);
        if (this.contextPropagationEnabled) {
            PROPAGATOR.inject(context, errorResp.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        }

        ctx.writeAndFlush(errorResp).addListener(_ -> {
            if (!keepAlive) {
                ctx.close();
            }
        });
    }

    private void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
        boolean keepAliveAllowed = keepAlive && this.httpServerConfig.get().headerKeepAliveEnabled();
        if (keepAliveAllowed) {
            if (!version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        } else {
            if (version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
        }
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    public Phaser getPhaser() {
        return phaser;
    }

    public AtomicInteger getActiveRequests() {
        return activeRequests;
    }
}
