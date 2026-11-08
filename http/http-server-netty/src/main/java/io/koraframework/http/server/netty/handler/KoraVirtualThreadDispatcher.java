package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.common.context.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.*;
import io.koraframework.logging.common.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import io.netty.util.concurrent.*;
import io.opentelemetry.api.trace.propagation.*;
import io.opentelemetry.context.*;

import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static io.koraframework.common.telemetry.Observation.OBSERVATION_KEY;
import static io.koraframework.common.telemetry.OpentelemetryContextStorage.*;
import static io.koraframework.http.server.netty.NettyContext.*;
import static io.koraframework.logging.common.MDC.*;
import static java.lang.IO.*;

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
        if (msg instanceof NettyTelemetryHandler.RequestContainer container) {
//            println("[DEBUG DISPATCHER] Request: " + container.nettyReq().method() + " " + container.nettyReq().uri() + ", autoRead before=" + ctx.channel().config().isAutoRead());

            if (shuttingDown) {
                var nettyReq = container.nettyReq();
                sendStaticErrorResponse(ctx, nettyReq.protocolVersion(), false, container.telemetryContext(), new IllegalStateException("Server is shutting down"));
                return;
            }

            ctx.channel().config().setAutoRead(false);

            activeRequests.incrementAndGet();
            phaser.register();
            container.nettyReq().retain();

            Thread.startVirtualThread(new DispatchRunnable(this, ctx, container));
            return;
        }

        ctx.fireChannelRead(msg);
    }

    private void dispatch(ChannelHandlerContext ctx, RequestContainer container) {
        var nettyReq = container.nettyReq();
        var rootCtx = container.telemetryContext();
        var invocation = container.invocation();
        var httpServerRequest = container.httpServerRequest();
        var observation = container.observation();

        var finalContext = KoraContext.empty()
            .with(NETTY_KEY, new NettyContext(nettyReq, ctx))
            .with(MDC_KEY, new MDC())
            .with(OPENTELEMETRY_KEY, rootCtx)
            .with(OBSERVATION_KEY, observation);

        KoraContext.runWith(finalContext, new ActionSupplier(this, ctx, container, invocation, httpServerRequest, observation, nettyReq, rootCtx));
    }

    private void sendStaticErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Context context, Throwable t) {
        var message = Objects.requireNonNullElse(t.getMessage(), "Unknown error");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        ByteBuf buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
        errorResp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

        setupConnectionHeader(errorResp, version, keepAlive);
        if (contextPropagationEnabled) {
            PROPAGATOR.inject(context, errorResp.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        }

        ctx.writeAndFlush(errorResp).addListener(new CloseOrAutoReadListener(ctx, keepAlive));
    }

    private void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
        boolean keepAliveAllowed = keepAlive && httpServerConfig.get().headerKeepAliveEnabled();
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

    private static final class DispatchRunnable implements Runnable {
        private final KoraVirtualThreadDispatcher dispatcher;
        private final ChannelHandlerContext ctx;
        private final RequestContainer container;

        public DispatchRunnable(KoraVirtualThreadDispatcher dispatcher, ChannelHandlerContext ctx, RequestContainer container) {
            this.dispatcher = dispatcher;
            this.ctx = ctx;
            this.container = container;
        }

        @Override
        public void run() {
            try {
                dispatcher.dispatch(ctx, container);
            } finally {
                ReferenceCountUtil.release(container.nettyReq());
                dispatcher.activeRequests.decrementAndGet();
                dispatcher.phaser.arriveAndDeregister();
            }
        }
    }

    private static final class ActionSupplier implements Supplier<Void> {
        private final KoraVirtualThreadDispatcher dispatcher;
        private final ChannelHandlerContext ctx;
        private final RequestContainer container;
        private final HttpServerInvocation invocation;
        private final HttpServerRequest httpServerRequest;
        private final HttpServerObservation observation;
        private final FullHttpRequest nettyReq;
        private final Context rootCtx;

        public ActionSupplier(KoraVirtualThreadDispatcher dispatcher, ChannelHandlerContext ctx, RequestContainer container,
                              HttpServerInvocation invocation, HttpServerRequest httpServerRequest,
                              HttpServerObservation observation, FullHttpRequest nettyReq, Context rootCtx) {
            this.dispatcher = dispatcher;
            this.ctx = ctx;
            this.container = container;
            this.invocation = invocation;
            this.httpServerRequest = httpServerRequest;
            this.observation = observation;
            this.nettyReq = nettyReq;
            this.rootCtx = rootCtx;
        }

        @Override
        public Void get() {
            HttpServerResponse response;
            try {
                response = invocation.proceed(httpServerRequest);
                if (observation != null) {
                    observation.observeResponse(response);
                }
            } catch (Throwable e) {
                if (e instanceof HttpServerResponse) {
                    response = (HttpServerResponse) e;
                    if (observation != null) {
                        observation.observeError(e);
                        observation.observeResponse(response);
                    }
                } else {
                    if (observation != null) {
                        var errorResponse = io.koraframework.http.server.common.response.HttpServerResponse.of(500);
                        observation.observeError(e);
                        observation.observeResponse(errorResponse);
                        observation.end();
                    }
                    dispatcher.sendStaticErrorResponse(ctx, nettyReq.protocolVersion(), HttpUtil.isKeepAlive(nettyReq), rootCtx, e);
                    return null;
                }
            }
            var responseContainer = new ResponseContainer(nettyReq, response, observation);
            ctx.executor().execute(new FireReadRunnable(ctx, responseContainer));
            return null;
        }
    }

    private static final class FireReadRunnable implements Runnable {
        private final ChannelHandlerContext ctx;
        private final ResponseContainer responseContainer;

        public FireReadRunnable(ChannelHandlerContext ctx, ResponseContainer responseContainer) {
            this.ctx = ctx;
            this.responseContainer = responseContainer;
        }

        @Override
        public void run() {
            ctx.fireChannelRead(responseContainer);
        }
    }

    private static final class CloseOrAutoReadListener implements GenericFutureListener<ChannelFuture> {
        private final ChannelHandlerContext ctx;
        private final boolean keepAlive;

        public CloseOrAutoReadListener(ChannelHandlerContext ctx, boolean keepAlive) {
            this.ctx = ctx;
            this.keepAlive = keepAlive;
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            if (!keepAlive) {
                ctx.close();
            } else {
                ctx.channel().config().setAutoRead(true);
            }
        }
    }
}
