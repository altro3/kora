package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.common.context.*;
import io.koraframework.http.common.body.*;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.request.*;
import io.koraframework.logging.common.MDC;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import io.opentelemetry.api.trace.propagation.*;
import io.opentelemetry.context.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

import static io.koraframework.common.telemetry.Observation.*;
import static io.koraframework.common.telemetry.OpentelemetryContextStorage.*;
import static io.koraframework.http.server.netty.NettyContext.*;
import static io.koraframework.logging.common.MDC.*;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.util.ReferenceCountUtil.*;
import static java.nio.charset.StandardCharsets.UTF_8;

@ChannelHandler.Sharable
public final class KoraVirtualThreadHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(KoraVirtualThreadHandler.class);
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

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
                var bytes = "Server is shutting down".getBytes(UTF_8);
                var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
                var errorResp = new DefaultFullHttpResponse(nettyReq.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
                ctx.writeAndFlush(errorResp);
            } finally {
                release(nettyReq);
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
        var streamingBody = ctx.channel().attr(NettyRequestStreamingHandler.ACTIVE_BODY_KEY).getAndSet(null);
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
                              HttpServerInvocation invocation, HttpServerRequest httpServerRequest,
                              HttpServerObservation observation, FullHttpRequest nettyReq, Context rootCtx) {
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
                            log.error("Fast-path serialization failed", t);
                        }
                    }
                }
            }

            try {
                handler.prepareAndSendResponse(ctx, nettyReq, nettyReq.protocolVersion(), HttpUtil.isKeepAlive(nettyReq), response, observation);
            } catch (Throwable t) {
                if (observation != null) {
                    observation.observeError(t);
                    observation.end();
                }
                if (body != null) {
                    try {body.close();} catch (IOException ignore) {}
                }
                safeRelease(nettyReq);
                ctx.close();
            }
            return null;
        }
    }

    private void prepareAndSendResponse(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpVersion version,
                                        boolean keepAlive, HttpServerResponse response, HttpServerObservation observation) throws Exception {
        var body = response.body();
        HttpResponseStatus status = HttpResponseStatus.valueOf(response.code());
        boolean isHead = nettyReq.method() == HttpMethod.HEAD;

        if (body == null) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), null);
            nettyResp.headers().set(CONTENT_LENGTH, 0);
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, null, observation);
            return;
        }

        var declaredLength = body.contentLength();
        var contentType = body.contentType();

        if (isHead) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), contentType);
            if (declaredLength >= 0) {
                nettyResp.headers().set(CONTENT_LENGTH, declaredLength);
            }
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, body, observation);
            return;
        }

        var content = body.getFullContentIfAvailable();
        if (content != null) {
            ByteBuf nettyBody = Unpooled.wrappedBuffer(content);
            var nettyResp = new DefaultFullHttpResponse(version, status, nettyBody);
            writeHeaders(nettyResp.headers(), response.headers(), contentType);
            nettyResp.headers().set(CONTENT_LENGTH, nettyBody.readableBytes());
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, body, observation);
            return;
        }

        var nettyResp = new DefaultHttpResponse(version, status);
        writeHeaders(nettyResp.headers(), response.headers(), contentType);
        if (declaredLength >= 0) {
            nettyResp.headers().set(CONTENT_LENGTH, declaredLength);
        } else {
            nettyResp.headers().set(TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        setupConnectionHeader(nettyResp, version, keepAlive);

        ctx.write(nettyResp);

        try (var output = new VirtualThreadNettyBodyOutputStream(ctx)) {
            body.write(output);
            output.flush();
        }

        var lastChunk = LastHttpContent.EMPTY_LAST_CONTENT;
        finalizeAndWrite(ctx, nettyReq, lastChunk, version, keepAlive, body, observation);
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, FullHttpRequest nettyReq, Object msg,
                                  HttpVersion version, boolean keepAlive, @Nullable HttpBodyOutput body,
                                  @Nullable HttpServerObservation observation) {
        if (msg instanceof HttpMessage httpMsg) {
            setupConnectionHeader(httpMsg, version, keepAlive);
        }

        if (keepAlive) {
            ctx.channel().config().setAutoRead(true);
        }

        ctx.writeAndFlush(msg).addListener(future -> {
            if (body != null) {
                try {body.close();} catch (IOException e) {log.error("Failed to close response body", e);}
            }
            if (observation != null) {
                if (!future.isSuccess()) {
                    observation.observeError(future.cause());
                }
                observation.end();
            }
            safeRelease(nettyReq);
            if (!future.isSuccess() || !keepAlive) {
                ctx.close();
            } else {
                ctx.read();
            }
        });
    }

    void writeHeaders(io.netty.handler.codec.http.HttpHeaders nettyHeaders, HttpHeaders koraHeaders, @Nullable String contentType) {
        if (this.httpServerConfig.get().headerServerNameEnabled()) {
            nettyHeaders.set(SERVER, HEADER_SERVER_VALUE);
        }
        for (var header : koraHeaders) {
            var key = header.getKey();
            if (isReservedHeader(key, contentType)) {
                continue;
            }
            nettyHeaders.add(key, header.getValue());
        }
        if (contentType != null) {
            nettyHeaders.set(CONTENT_TYPE, contentType);
        }
    }

    private static boolean isReservedHeader(String key, @Nullable String contentType) {
        return SERVER.contentEqualsIgnoreCase(key)
            || CONTENT_LENGTH.contentEqualsIgnoreCase(key)
            || TRANSFER_ENCODING.contentEqualsIgnoreCase(key)
            || (CONTENT_TYPE.contentEqualsIgnoreCase(key) && contentType != null);
    }

    private void sendStaticErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Context context, Throwable t) {
        var message = Objects.requireNonNullElse(t.getMessage(), "Unknown error");
        var bytes = message.getBytes(UTF_8);
        var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
        errorResp.headers().set(CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
        errorResp.headers().set(CONTENT_LENGTH, buffer.readableBytes());
        setupConnectionHeader(errorResp, version, keepAlive);
        if (contextPropagationEnabled) {
            PROPAGATOR.inject(context, errorResp.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        }
        ctx.writeAndFlush(errorResp).addListener(new CloseOrAutoReadListener(ctx, keepAlive));
    }

    void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
        boolean keepAliveAllowed = keepAlive && httpServerConfig.get().headerKeepAliveEnabled();
        if (keepAliveAllowed) {
            if (!version.isKeepAliveDefault()) {
                response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        } else {
            if (version.isKeepAliveDefault()) {
                response.headers().set(CONNECTION, HttpHeaderValues.CLOSE);
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
                ctx.read();
            }
        }
    }

    private static final class VirtualThreadNettyBodyOutputStream extends OutputStream {
        private final ChannelHandlerContext ctx;
        private final CompositeByteBuf compositeBuffer;
        private final int chunkThreshold = 8192;

        public VirtualThreadNettyBodyOutputStream(ChannelHandlerContext ctx) {
            this.ctx = ctx;
            this.compositeBuffer = ctx.alloc().compositeBuffer();
        }

        @Override
        public void write(int b) throws IOException {
            ensureWritability();
            ByteBuf buf = ctx.alloc().buffer(1);
            buf.writeByte(b);
            compositeBuffer.addComponent(true, buf);
            checkFlushThreshold();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            ensureWritability();
            ByteBuf buf = ctx.alloc().buffer(len);
            buf.writeBytes(b, off, len);
            compositeBuffer.addComponent(true, buf);
            checkFlushThreshold();
        }

        private void checkFlushThreshold() {
            if (compositeBuffer.readableBytes() >= chunkThreshold) {
                flushBuffer();
            }
        }

        private void flushBuffer() {
            if (compositeBuffer.readableBytes() > 0) {
                ByteBuf chunk = compositeBuffer.copy();
                compositeBuffer.clear();
                ctx.writeAndFlush(new DefaultHttpContent(chunk));
            }
        }

        @Override
        public void flush() {
            flushBuffer();
        }

        private void ensureWritability() {
            while (!ctx.channel().isWritable() && ctx.channel().isActive()) {
                LockSupport.parkNanos(1_000_000);
            }
        }

        @Override
        public void close() {
            flushBuffer();
            compositeBuffer.release();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        phaser.arriveAndAwaitAdvance();
        vtExecutor.shutdown();
    }
}
