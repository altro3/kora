package io.koraframework.http.server.netty.handler;

import io.koraframework.common.telemetry.Observation;
import io.koraframework.common.telemetry.OpentelemetryContext;
import io.koraframework.http.common.HttpResultCode;
import io.koraframework.http.common.body.HttpBodyOutput;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.common.router.HttpServerRouter;
import io.koraframework.http.server.common.telemetry.HttpServerObservation;
import io.koraframework.http.server.common.telemetry.HttpServerTelemetry;
import io.koraframework.http.server.common.telemetry.impl.NoopHttpServerObservation;
import io.koraframework.http.server.common.telemetry.impl.NoopHttpServerTelemetry;
import io.koraframework.http.server.netty.NettyContext;
import io.koraframework.http.server.netty.request.NettyUnroutedHttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

@ChannelHandler.Sharable
public final class KoraHttpServerHandler implements NettyHttpHandler {

    private static final Logger log = LoggerFactory.getLogger(KoraHttpServerHandler.class);
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    private final HttpServerConfig httpServerConfig;
    private final HttpServerTelemetry telemetry;
    private final HttpServerRouter httpServerRouter;
    private final boolean telemetryEnabled;
    private final boolean contextPropagationEnabled;

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Phaser phaser = new Phaser(1);
    private volatile boolean shuttingDown = false;

    public KoraHttpServerHandler(
        HttpServerConfig httpServerConfig,
        HttpServerRouter httpServerRouter,
        HttpServerTelemetry telemetry
    ) {
        this.httpServerConfig = httpServerConfig;
        this.httpServerRouter = httpServerRouter;
        this.telemetry = telemetry;
        this.telemetryEnabled = !(telemetry instanceof NoopHttpServerTelemetry);
        this.contextPropagationEnabled = this.telemetryEnabled;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest nettyReq)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (shuttingDown) {
            sendServiceUnavailable(ctx, nettyReq);
            return;
        }

        activeRequests.incrementAndGet();
        phaser.register();

        nettyReq.retain();

        var version = nettyReq.protocolVersion();
        var keepAlive = HttpUtil.isKeepAlive(nettyReq);
        var koraReq = new NettyUnroutedHttpRequest(nettyReq);

        Thread.startVirtualThread(() -> {
            try {
                processRequest(ctx, nettyReq, koraReq, version, keepAlive);
            } finally {
                ReferenceCountUtil.release(nettyReq);
                activeRequests.decrementAndGet();
                phaser.arriveAndDeregister();
            }
        });
    }

    private void processRequest(ChannelHandlerContext ctx, FullHttpRequest nettyReq, NettyUnroutedHttpRequest koraReq, HttpVersion version, boolean keepAlive) {
        var rootCtx = this.contextPropagationEnabled
            ? PROPAGATOR.extract(Context.root(), nettyReq.headers(), NettyHttpHeaderMapExchange.INSTANCE)
            : Context.root();

        ScopedValue.where(NettyContext.VALUE, new NettyContext(nettyReq, ctx))
            .where(io.koraframework.logging.common.MDC.VALUE, new io.koraframework.logging.common.MDC())
            .where(OpentelemetryContext.VALUE, rootCtx)
            .run(() -> {
                HttpServerObservation observation = NoopHttpServerObservation.INSTANCE;
                Context activeCtx = rootCtx;
                try {
                    var invocation = this.httpServerRouter.route(koraReq);
                    observation = this.telemetry.observe(invocation.routedRequest());
                    activeCtx = rootCtx.with(observation.span());

                    final HttpServerObservation finalObservation = observation;
                    final Context finalActiveCtx = activeCtx;

                    ScopedValue.where(OpentelemetryContext.VALUE, activeCtx)
                        .where(Observation.VALUE, observation)
                        .run(() -> {
                            HttpServerResponse response;
                            try {
                                var httpServerRequest = finalObservation.observeRequest(invocation.routedRequest());
                                response = invocation.proceed(httpServerRequest);
                            } catch (Throwable e) {
                                finalObservation.observeError(e);
                                if (e instanceof HttpServerResponse rs) {
                                    response = rs;
                                } else {
                                    sendErrorResponse(ctx, version, keepAlive, finalObservation, finalActiveCtx, e);
                                    return;
                                }
                            }
                            prepareAndSendResponse(ctx, version, keepAlive, finalObservation, finalActiveCtx, response, nettyReq.method() == HttpMethod.HEAD);
                        });
                } catch (Throwable t) {
                    log.error("Critical HTTP pipeline failure", t);
                    sendErrorResponse(ctx, version, keepAlive, observation, activeCtx, t);
                } finally {
                    MDC.clear();
                }
            });
    }

    private void prepareAndSendResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, HttpServerObservation observation, Context context, HttpServerResponse response, boolean isHead) {
        response = observation.observeResponse(response);
        var body = response.body();
        HttpResponseStatus status = HttpResponseStatus.valueOf(response.code());

        if (body == null) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), null);
            nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            finalizeAndWrite(ctx, nettyResp, version, keepAlive, context, observation, null);
            return;
        }

        var declaredLength = body.contentLength();
        var contentType = body.contentType();

        if (isHead) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), contentType);
            if (declaredLength >= 0) {
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, declaredLength);
            }
            finalizeAndWrite(ctx, nettyResp, version, keepAlive, context, observation, body);
            return;
        }

        try {
            var content = body.getFullContentIfAvailable();
            if (content != null) {
                ByteBuf nettyBody = Unpooled.wrappedBuffer(content);
                var nettyResp = new DefaultFullHttpResponse(version, status, nettyBody);
                writeHeaders(nettyResp.headers(), response.headers(), contentType);
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, nettyBody.readableBytes());
                finalizeAndWrite(ctx, nettyResp, version, keepAlive, context, observation, body);
                return;
            }

            var nettyResp = new DefaultHttpResponse(version, status);
            writeHeaders(nettyResp.headers(), response.headers(), contentType);
            if (declaredLength >= 0) {
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, declaredLength);
            } else {
                nettyResp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
            setupConnectionHeader(nettyResp, version, keepAlive);
            if (this.contextPropagationEnabled) {
                PROPAGATOR.inject(context, nettyResp.headers(), NettyHttpHeaderMapExchange.INSTANCE);
            }

            var output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, observation, body);
            body.write(output);
            output.complete();

        } catch (Throwable e) {
            observation.observeError(e);
            closeBody(observation, body);
            sendErrorResponse(ctx, version, keepAlive, observation, context, e);
        }
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, HttpMessage msg, HttpVersion version, boolean keepAlive,
                                  Context context, HttpServerObservation observation, @Nullable HttpBodyOutput body) {
        setupConnectionHeader(msg, version, keepAlive);
        if (this.contextPropagationEnabled) {
            PROPAGATOR.inject(context, msg.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        }

        ctx.writeAndFlush(msg).addListener(future -> {
            closeBody(observation, body);
            if (future.isSuccess()) {
                observation.end();
                if (!keepAlive) {
                    ctx.close();
                }
            } else {
                observation.observeResultCode(HttpResultCode.CONNECTION_ERROR);
                observation.observeError(future.cause());
                observation.end();
                ctx.close();
            }
        });
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive,
                                   HttpServerObservation observation, Context context, Throwable t) {
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

        ctx.writeAndFlush(errorResp).addListener(future -> {
            if (!future.isSuccess()) {
                observation.observeResultCode(HttpResultCode.CONNECTION_ERROR);
                observation.observeError(future.cause());
            }
            observation.end();
            ctx.close();
        });
    }

    private void sendServiceUnavailable(ChannelHandlerContext ctx, FullHttpRequest nettyReq) {
        var version = nettyReq.protocolVersion();
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.SERVICE_UNAVAILABLE);
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        errorResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(errorResp).addListener(ChannelFutureListener.CLOSE);
        ReferenceCountUtil.release(nettyReq);
    }

    private void writeHeaders(HttpHeaders nettyHeaders, io.koraframework.http.common.header.HttpHeaders koraHeaders, @Nullable String contentType) {
        if (this.httpServerConfig.headerServerNameEnabled()) {
            nettyHeaders.set(HttpHeaderNames.SERVER, HEADER_SERVER_VALUE);
        }
        for (var header : koraHeaders) {
            var key = header.getKey();
            if (isReservedHeader(key, contentType)) {
                continue;
            }
            nettyHeaders.add(key, header.getValue());
        }
        if (contentType != null) {
            nettyHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
    }

    private static boolean isReservedHeader(String key, @Nullable String contentType) {
        if (HttpHeaderNames.SERVER.contentEqualsIgnoreCase(key)
            || HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(key)
            || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(key)) {
            return true;
        }
        return HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(key) && contentType != null;
    }

    private void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
        if (keepAlive) {
            if (!version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        } else {
            if (version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
        }
    }

    public static void closeBody(HttpServerObservation observation, @Nullable HttpBodyOutput body) {
        if (body != null) {
            try {
                body.close();
            } catch (IOException e) {
                observation.observeError(e);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty pipeline exception caught", cause);
        ctx.close();
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    public AtomicInteger getActiveRequests() {
        return activeRequests;
    }

    public Phaser getPhaser() {
        return phaser;
    }
}
