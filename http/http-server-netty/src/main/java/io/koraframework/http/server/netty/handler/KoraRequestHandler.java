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
import io.koraframework.http.server.netty.body.*;
import io.koraframework.http.server.netty.buffer.*;
import io.koraframework.http.server.netty.request.*;
import io.koraframework.logging.common.MDC;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.*;
import io.netty.handler.codec.compression.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import io.opentelemetry.api.trace.propagation.*;
import io.opentelemetry.context.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static io.koraframework.common.telemetry.Observation.*;
import static io.koraframework.common.telemetry.OpentelemetryContextStorage.*;
import static io.koraframework.http.server.netty.NettyContext.*;
import static io.koraframework.logging.common.MDC.*;

public final class KoraRequestHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(KoraRequestHandler.class);
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final ValueOf<KoraNettyServerConfig> nettyServerConfig;
    private final boolean contextPropagationEnabled;
    private final HttpServerRouter currentRouter;
    private final HttpServerTelemetry telemetry;
    private final ThreadFactory virtualThreadFactory;

    public KoraRequestHandler(
        ValueOf<HttpServerConfig> httpServerConfig,
        ValueOf<KoraNettyServerConfig> nettyServerConfig,
        boolean contextPropagationEnabled,
        HttpServerRouter currentRouter,
        HttpServerTelemetry telemetry,
        ThreadFactory virtualThreadFactory
    ) {
        this.httpServerConfig = Objects.requireNonNull(httpServerConfig, "httpServerConfig");
        this.nettyServerConfig = Objects.requireNonNull(nettyServerConfig, "nettyServerConfig");
        this.contextPropagationEnabled = contextPropagationEnabled;
        this.currentRouter = Objects.requireNonNull(currentRouter, "currentRouter");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.virtualThreadFactory = Objects.requireNonNull(virtualThreadFactory, "virtualThreadFactory");
    }

    @Override
    public void accept(ChannelHandlerContext ctx, HttpRequest request, @Nullable AutoCloseable body, KoraOutboundAccess outboundAccess) {
        var config = httpServerConfig.get();
        var nettyConfig = nettyServerConfig.get();
        var version = request.protocolVersion();

        if (nettyConfig.rejectUnsupportedHttpVersions() && version.majorVersion() != 1) {
            closeQuietly(body);
            outboundAccess.closeAfterWrite();
            writeSimpleError(ctx, outboundAccess, HttpVersion.HTTP_1_1, HttpResponseStatus.HTTP_VERSION_NOT_SUPPORTED, "Unsupported HTTP version: " + version.text());
            return;
        }

        if (request.decoderResult().isFailure()) {
            closeQuietly(body);
            outboundAccess.closeAfterWrite();
            var cause = request.decoderResult().cause();
            var status = cause instanceof TooLongFrameException ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE : HttpResponseStatus.BAD_REQUEST;
            writeSimpleError(ctx, outboundAccess, version, status, Objects.requireNonNullElse(cause.getMessage(), "Malformed HTTP request"));
            return;
        }

        var requestBody = new NettyRequestHttpBody(request, body);
        try {
            var unregisterAbandon = new AtomicReference<Runnable>();
            var thread = virtualThreadFactory.newThread(() -> {
                try (requestBody) {
                    dispatch(ctx, request, requestBody, outboundAccess);
                } catch (Throwable t) {
                    log.error("CRITICAL: Error inside Kora dispatch pipeline (Loom Thread)", t);
                    outboundAccess.abort();
                } finally {
                    var cleanup = unregisterAbandon.get();
                    if (cleanup != null) {
                        cleanup.run();
                    }
                }
            });
            unregisterAbandon.set(outboundAccess.whenAbandoned(thread::interrupt));
            thread.start();
        } catch (Throwable t) {
            log.error("CRITICAL: Failed to create or start virtual thread", t);
            requestBody.close();
            writeFallbackResponse(outboundAccess, t);
        }
    }

    private void dispatch(ChannelHandlerContext ctx, HttpRequest nettyReq, NettyRequestHttpBody requestBody, KoraOutboundAccess outboundAccess) {
        NettyUnroutedHttpRequest koraReq;
        try {
            koraReq = new NettyUnroutedHttpRequest(nettyReq, requestBody);
        } catch (IllegalArgumentException e) {
            outboundAccess.closeAfterWrite();
            writeSimpleError(ctx, outboundAccess, nettyReq.protocolVersion(), HttpResponseStatus.BAD_REQUEST, Objects.requireNonNullElse(e.getMessage(), "Invalid request URI"));
            return;
        }

        var invocation = currentRouter.route(koraReq);
        var httpServerRequest = invocation.routedRequest();
        var observation = telemetry.observe(httpServerRequest);
        outboundAccess.attachment(observation);

        var httpServerRequestObserved = observation.observeRequest(httpServerRequest);
        var rootCtx = PROPAGATOR.extract(Context.root(), nettyReq.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        var activeCtx = rootCtx.with(observation.span());

        var finalContext = KoraContext.empty()
            .with(NETTY_KEY, new NettyContext(nettyReq, ctx))
            .with(MDC_KEY, new MDC())
            .with(OPENTELEMETRY_KEY, activeCtx)
            .with(OBSERVATION_KEY, observation);

        KoraContext.runWith(finalContext, () -> {
            executeAndSend(ctx, invocation, httpServerRequestObserved, observation, nettyReq, outboundAccess, activeCtx);
            return null;
        });
    }

    private void executeAndSend(
        ChannelHandlerContext ctx,
        HttpServerInvocation invocation,
        HttpServerRequest httpServerRequestObserved,
        HttpServerObservation observation,
        HttpRequest nettyReq,
        KoraOutboundAccess outboundAccess,
        Context activeCtx
    ) {
        HttpServerResponse response;
        try {
            response = invocation.proceed(httpServerRequestObserved);
            observation.observeResponse(response);
            var body = response.body();
            if (body != null) {
                var availableContent = body.getFullContentIfAvailable();
                if (availableContent != null) {
                    response = HttpServerResponse.of(
                        response.code(),
                        response.headers(),
                        new DefaultFullHttpBody(availableContent, body.contentType())
                    );
                }
            }
        } catch (Throwable e) {
            if (e instanceof HttpServerResponse resp) {
                response = resp;
                observation.observeError(e);
                observation.observeResponse(response);
            } else {
                log.error("Internal server error during request dispatching", e);
                var errorResponse = HttpServerResponse.of(500);
                observation.observeError(e);
                observation.observeResponse(errorResponse);
                sendStaticErrorResponse(ctx, outboundAccess, nettyReq.protocolVersion(), activeCtx, e);
                return;
            }
        }

        try {
            prepareAndSendResponse(ctx, nettyReq, response, outboundAccess);
        } catch (Throwable t) {
            log.error("Failed to prepare or send HTTP response", t);
            observation.observeError(t);
            var body = response.body();
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                }
            }
            writeFallbackResponse(outboundAccess, t);
        }
    }

    private void prepareAndSendResponse(
        ChannelHandlerContext ctx,
        HttpRequest nettyReq,
        HttpServerResponse response,
        KoraOutboundAccess outboundAccess
    ) throws IOException {
        var version = nettyReq.protocolVersion();
        var status = HttpResponseStatus.valueOf(response.code());
        boolean isHead = nettyReq.method() == HttpMethod.HEAD;

        if (nettyReq.decoderResult().isFailure() || response.code() >= 500) {
            outboundAccess.closeAfterWrite();
        }

        var body = response.body();
        if (body == null) {
            var nettyResp = new DefaultHttpResponse(version, status);
            writeHeaders(nettyResp.headers(), response, null);
            outboundAccess.write(nettyResp, (KoraAvailableBody) null);
            return;
        }

        var declaredLength = body.contentLength();
        var contentType = body.contentType();

        if (isHead) {
            try (body) {
                var nettyResp = new DefaultHttpResponse(version, status);
                writeHeaders(nettyResp.headers(), response, contentType);
                if (declaredLength >= 0) {
                    nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, declaredLength);
                }
                outboundAccess.writeHeadResponse(nettyResp);
            }
            return;
        }

        var content = body.getFullContentIfAvailable();
        if (content != null) {
            try (body) {
                var buf = content.hasRemaining() ? Unpooled.wrappedBuffer(content) : Unpooled.EMPTY_BUFFER;
                var availableBody = new KoraAvailableBody(new KoraReadBuffer(buf));
                var nettyResp = new DefaultHttpResponse(version, status);
                writeHeaders(nettyResp.headers(), response, contentType);
                outboundAccess.write(nettyResp, availableBody);
            }
            return;
        }

        var nettyResp = new DefaultHttpResponse(version, status);
        writeHeaders(nettyResp.headers(), response, contentType);
        if (declaredLength >= 0) {
            nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, declaredLength);
        }

        var os = new BackpressuredOutputStream(ctx.alloc());
        outboundAccess.write(nettyResp, os);
        streamOutputBodyToNetty(body, os);
    }

    private void streamOutputBodyToNetty(HttpBodyOutput body, BackpressuredOutputStream os) {
        try (body; os) {
            body.write(os);
            os.flushCurrent();
            os.complete();
        } catch (Throwable t) {
            os.fail(t);
        }
    }

    private void writeHeaders(HttpHeaders nettyHeaders, HttpServerResponse response, @Nullable String contentType) {
        if (httpServerConfig.get().headerServerNameEnabled()) {
            nettyHeaders.set(HttpHeaderNames.SERVER, HEADER_SERVER_VALUE);
        }
        for (var header : response.headers()) {
            var key = header.getKey();
            if (HttpHeaderNames.SERVER.contentEqualsIgnoreCase(key)
                || HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(key)
                || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(key)
                || (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(key) && contentType != null)) {
                continue;
            }
            nettyHeaders.add(AsciiString.cached(key), header.getValue());
        }
        if (contentType != null) {
            nettyHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
    }

    private void sendStaticErrorResponse(
        ChannelHandlerContext ctx,
        KoraOutboundAccess outboundAccess,
        HttpVersion version,
        Context context,
        Throwable t
    ) {
        var message = Objects.requireNonNullElse(t.getMessage(), "Unknown error");
        var bytes = message.getBytes(StandardCharsets.UTF_8);
        var buffer = ctx.alloc().buffer(bytes.length).writeBytes(bytes);
        var availableBody = new KoraAvailableBody(new KoraReadBuffer(buffer));
        var errorResp = new DefaultHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        errorResp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
        if (contextPropagationEnabled) {
            PROPAGATOR.inject(context, errorResp.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        }
        outboundAccess.closeAfterWrite();
        outboundAccess.write(errorResp, availableBody);
    }

    private static void writeSimpleError(
        ChannelHandlerContext ctx,
        KoraOutboundAccess outboundAccess,
        HttpVersion version,
        HttpResponseStatus status,
        String message
    ) {
        var bytes = message.getBytes(StandardCharsets.UTF_8);
        var buffer = ctx.alloc().buffer(bytes.length).writeBytes(bytes);
        var availableBody = new KoraAvailableBody(new KoraReadBuffer(buffer));
        var errorResp = new DefaultHttpResponse(version, status);
        errorResp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
        outboundAccess.write(errorResp, availableBody);
    }

    private static void writeFallbackResponse(KoraOutboundAccess outboundAccess, Throwable failure) {
        try {
            outboundAccess.closeAfterWrite();
            outboundAccess.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR), (KoraAvailableBody) null);
        } catch (Throwable e) {
            failure.addSuppressed(e);
            try {
                outboundAccess.abort();
            } catch (Throwable g) {
                failure.addSuppressed(g);
            }
        }
    }

    @Override
    public void responseWritten(@Nullable Object attachment) {
        if (attachment instanceof HttpServerObservation observation) {
            observation.end();
        }
    }

    @Override
    public void responseFailed(@Nullable Object attachment, Throwable cause) {
        if (attachment instanceof HttpServerObservation observation) {
            observation.observeError(cause);
            observation.end();
        }
    }

    @Override
    public void handleUnboundError(Throwable cause) {
        if (isIgnorable(cause)) {
            if (log.isDebugEnabled()) {
                log.debug("Swallowed an IOException caused by client connectivity: {}", cause.getMessage(), cause);
            }
            return;
        }
        if (cause instanceof DecompressionException) {
            if (log.isDebugEnabled()) {
                log.debug("Netty HTTP Server Error - Decompression failed: {}", cause.getMessage(), cause);
            }
        } else {
            log.error("Netty HTTP Server Error - No request state present: {}", cause.getMessage(), cause);
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isIgnorable(Throwable cause) {
        if (cause instanceof ClosedChannelException || cause.getCause() instanceof ClosedChannelException) {
            return true;
        }
        var message = cause.getMessage();
        if (!(cause instanceof IOException) || message == null) {
            return false;
        }
        return containsIgnoreCase(message, "broken pipe")
            || containsConnectionError(message);
    }

    private static boolean containsConnectionError(String text) {
        var prefix = "connection ";
        int max = text.length() - prefix.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, prefix, 0, prefix.length())) {
                int next = i + prefix.length();
                if (text.regionMatches(true, next, "reset", 0, 5)
                    || text.regionMatches(true, next, "closed", 0, 6)
                    || text.regionMatches(true, next, "abort", 0, 5)
                    || text.regionMatches(true, next, "broken", 0, 6)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String text, String target) {
        int max = text.length() - target.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, target, 0, target.length())) {
                return true;
            }
        }
        return false;
    }

    private static final class BackpressuredOutputStream extends OutputStream implements Flow.Publisher<KoraReadBuffer> {
        private static final int CHUNK_SIZE = 8192;

        private final ByteBufAllocator alloc;
        private final Semaphore demand = new Semaphore(0);
        private final AtomicBoolean subscribed = new AtomicBoolean(false);
        private volatile Flow.@Nullable Subscriber<? super KoraReadBuffer> subscriber;
        private volatile boolean cancelled;
        @Nullable
        private ByteBuf current;

        BackpressuredOutputStream(ByteBufAllocator alloc) {
            this.alloc = alloc;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super KoraReadBuffer> sub) {
            Objects.requireNonNull(sub, "sub");
            if (!subscribed.compareAndSet(false, true)) {
                sub.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                sub.onError(new IllegalStateException("Already subscribed"));
                return;
            }
            subscriber = sub;
            sub.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0) {
                        cancelled = true;
                        demand.release(1);
                        sub.onError(new IllegalArgumentException("Spec rule 3.9: n must be > 0"));
                        return;
                    }
                    int permits = n >= Integer.MAX_VALUE ? Integer.MAX_VALUE - demand.availablePermits() : (int) Math.min(n, Integer.MAX_VALUE - demand.availablePermits());
                    if (permits > 0) {
                        demand.release(permits);
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                    demand.release(1);
                }
            });
        }

        private ByteBuf ensureBuffer() {
            var buf = current;
            if (buf == null) {
                buf = alloc.buffer(CHUNK_SIZE);
                current = buf;
            }
            return buf;
        }

        void flushCurrent() throws IOException {
            var buf = current;
            if (buf == null) {
                return;
            }
            current = null;
            if (!buf.isReadable()) {
                buf.release();
                return;
            }
            try {
                demand.acquire();
            } catch (InterruptedException e) {
                buf.release();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while streaming response body");
            }
            if (cancelled) {
                buf.release();
                throw new IOException("Response stream cancelled by client");
            }
            var sub = Objects.requireNonNull(subscriber);
            sub.onNext(new KoraReadBuffer(buf));
        }

        void complete() {
            var sub = subscriber;
            if (sub != null && !cancelled) {
                sub.onComplete();
            }
        }

        void fail(Throwable t) {
            var sub = subscriber;
            if (sub != null && !cancelled) {
                sub.onError(t);
            }
        }

        @Override
        public void write(int b) throws IOException {
            if (cancelled) {
                throw new IOException("Response stream cancelled by client");
            }
            var buf = ensureBuffer();
            buf.writeByte(b);
            if (!buf.isWritable()) {
                flushCurrent();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                if (cancelled) {
                    throw new IOException("Response stream cancelled by client");
                }
                var buf = ensureBuffer();
                int toWrite = Math.min(remaining, buf.writableBytes());
                buf.writeBytes(b, offset, toWrite);
                offset += toWrite;
                remaining -= toWrite;
                if (!buf.isWritable()) {
                    flushCurrent();
                }
            }
        }

        @Override
        public void flush() throws IOException {
            flushCurrent();
        }

        @Override
        public void close() {
            var buf = current;
            if (buf != null) {
                current = null;
                buf.release();
            }
        }
    }
}
