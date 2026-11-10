package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.common.context.*;
import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.request.*;
import io.koraframework.logging.common.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import io.opentelemetry.api.trace.propagation.*;
import io.opentelemetry.context.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static io.koraframework.common.telemetry.Observation.*;
import static io.koraframework.common.telemetry.OpentelemetryContextStorage.*;
import static io.koraframework.http.server.netty.NettyContext.*;
import static io.koraframework.logging.common.MDC.*;
import static io.netty.util.ReferenceCountUtil.*;
import static java.lang.IO.*;

@ChannelHandler.Sharable
public final class KoraVirtualThreadHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final boolean contextPropagationEnabled;
    private final HttpServerRouter currentRouter;
    private final HttpServerTelemetry telemetry;

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Phaser phaser = new Phaser(1);
    private volatile boolean shuttingDown = false;
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public KoraVirtualThreadHandler(ValueOf<HttpServerConfig> httpServerConfig, boolean contextPropagationEnabled,
                                    HttpServerRouter currentRouter, HttpServerTelemetry telemetry) {
        super(false);
        this.httpServerConfig = httpServerConfig;
        this.contextPropagationEnabled = contextPropagationEnabled;
        this.currentRouter = currentRouter;
        this.telemetry = telemetry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest nettyReq) {
        if (shuttingDown) {
            try {
                var bytes = "Server is shutting down".getBytes(StandardCharsets.UTF_8);
                var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
                var errorResp = new DefaultFullHttpResponse(nettyReq.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
                ctx.writeAndFlush(errorResp);
            } finally {
                ReferenceCountUtil.release(nettyReq);
            }
            return;
        }

        activeRequests.incrementAndGet();
        phaser.register();
        nettyReq.retain();

        vtExecutor.execute(() -> {
            try {
                dispatch(ctx, nettyReq);
            } finally {
                activeRequests.decrementAndGet();
                phaser.arriveAndDeregister();
            }
        });

        ctx.read();
    }

    private void dispatch(ChannelHandlerContext ctx, FullHttpRequest nettyReq) {
        var rootCtx = PROPAGATOR.extract(Context.root(), nettyReq.headers(), NettyHttpHeaderMapExchange.INSTANCE);

        NettyRequestHttpBody streamingBody = ctx.channel().attr(NettyRequestStreamingHandler.ACTIVE_BODY_KEY).get();
        var koraReq = new NettyUnroutedHttpRequest(nettyReq, streamingBody);

        var invocation = currentRouter.route(koraReq);
        var httpServerRequest = invocation.routedRequest();
        var observation = telemetry.observe(httpServerRequest);
        var httpServerRequestObserved = observation.observeRequest(httpServerRequest);
        var activeCtx = rootCtx.with(observation.span());

        var finalContext = KoraContext.empty()
            .with(NETTY_KEY, new NettyContext(nettyReq, ctx))
            .with(MDC_KEY, new MDC())
            .with(OPENTELEMETRY_KEY, activeCtx)
            .with(OBSERVATION_KEY, observation);

        KoraContext.runWith(finalContext, new ActionSupplier(this, ctx, invocation, httpServerRequestObserved, observation, nettyReq, activeCtx));
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        boolean writable = ctx.channel().isWritable();
        ctx.channel().config().setAutoRead(writable);
        if (writable) {
            ctx.read();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ctx.channel().isActive()) {
            ctx.close();
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

    private static final class ActionSupplier implements Supplier<Void> {
        private final KoraVirtualThreadHandler handler;
        private final ChannelHandlerContext ctx;
        private final HttpServerInvocation invocation;
        private final HttpServerRequest httpServerRequest;
        private final HttpServerObservation observation;
        private final FullHttpRequest nettyReq;
        private final Context rootCtx;

        public ActionSupplier(KoraVirtualThreadHandler handler, ChannelHandlerContext ctx,
                              HttpServerInvocation invocation,
                              HttpServerRequest httpServerRequest,
                              HttpServerObservation observation,
                              FullHttpRequest nettyReq, Context rootCtx) {
            this.handler = handler;
            this.ctx = ctx;
            this.invocation = invocation;
            this.httpServerRequest = httpServerRequest;
            this.observation = observation;
            this.nettyReq = nettyReq;
            this.rootCtx = rootCtx;
        }

        @Override
        public Void get() {
            var httpServerRequestObserved = observation.observeRequest(httpServerRequest);

            HttpServerResponse response;
            try {
                response = invocation.proceed(httpServerRequestObserved);
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
                        var errorResponse = HttpServerResponse.of(500);
                        observation.observeError(e);
                        observation.observeResponse(errorResponse);
                        observation.end();
                    }
                    try {
                        handler.sendStaticErrorResponse(ctx, nettyReq.protocolVersion(), HttpUtil.isKeepAlive(nettyReq), rootCtx, e);
                    } finally {
                        safeRelease(nettyReq);
                    }
                    return null;
                }
            }

            HttpBodyOutput body = response.body();
            if (body != null) {
                ByteBuffer availableContent = body.getFullContentIfAvailable();
                if (availableContent != null) {
                    response = HttpServerResponse.of(response.code(), response.headers(), new DefaultFullHttpBody(availableContent, body.contentType()));
                } else {
                    long contentLength = body.contentLength();
                    if (contentLength <= 64 * 1024) {
                        int initialCapacity = contentLength > 0 ? (int) contentLength : 1024;
                        try (var baos = new ByteArrayOutputStream(initialCapacity)) {
                            body.write(baos);
                            byte[] bytes = baos.toByteArray();
                            ByteBuffer nioBuffer = ByteBuffer.wrap(bytes);
                            response = HttpServerResponse.of(response.code(), response.headers(), new DefaultFullHttpBody(nioBuffer, body.contentType()));
                        } catch (Throwable t) {
                            println("Fast-path serialization failed" + Arrays.toString(t.getStackTrace()));
                        }
                    }
                }
            }
            var responseContainer = new ResponseContainer(nettyReq, response, observation);
            ctx.channel().writeAndFlush(responseContainer);
            return null;
        }
    }

    private void sendStaticErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Context context, Throwable t) {
        var message = Objects.requireNonNullElse(t.getMessage(), "Unknown error");
        var bytes = message.getBytes(StandardCharsets.UTF_8);

        var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
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

    private static final class CloseOrAutoReadListener implements ChannelFutureListener {
        private final ChannelHandlerContext ctx;
        private final boolean keepAlive;

        public CloseOrAutoReadListener(ChannelHandlerContext ctx, boolean keepAlive) {
            this.ctx = ctx;
            this.keepAlive = keepAlive;
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) {
                ctx.close();
                return;
            }

            if (!keepAlive) {
                ctx.close();
            } else {
                ctx.channel().config().setAutoRead(true);
            }
        }
    }
}
