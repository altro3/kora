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
import io.koraframework.netty.common.loom.*;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static io.koraframework.common.telemetry.Observation.*;
import static io.koraframework.common.telemetry.OpentelemetryContextStorage.*;
import static io.koraframework.http.server.netty.NettyContext.*;
import static io.koraframework.logging.common.MDC.*;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.util.ReferenceCountUtil.*;
import static java.nio.charset.StandardCharsets.*;

@ChannelHandler.Sharable
public final class KoraVirtualThreadHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(KoraVirtualThreadHandler.class);
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    public static final AttributeKey<NettyRequestHttpBody> ACTIVE_BODY_KEY = AttributeKey.valueOf("kora.active.body");
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final boolean contextPropagationEnabled;
    private final HttpServerRouter currentRouter;
    private final HttpServerTelemetry telemetry;
    private final ThreadFactory virtualThreadFactory;

    private volatile boolean shuttingDown = false;

    public KoraVirtualThreadHandler(ValueOf<HttpServerConfig> httpServerConfig, boolean contextPropagationEnabled,
                                    HttpServerRouter currentRouter, HttpServerTelemetry telemetry, ThreadFactory virtualThreadFactory) {
        this.httpServerConfig = httpServerConfig;
        this.contextPropagationEnabled = contextPropagationEnabled;
        this.currentRouter = currentRouter;
        this.telemetry = telemetry;
        this.virtualThreadFactory = virtualThreadFactory;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest nettyReq) {
            if (nettyReq.decoderResult().isFailure()) {
                log.error("HTTP Request decoding failed: {}", nettyReq.decoderResult().cause().getMessage());
                sendStaticErrorAndClose(ctx, nettyReq);
                return;
            }
            if (shuttingDown) {
                sendStaticErrorAndClose(ctx, nettyReq);
                return;
            }

            var body = new NettyRequestHttpBody(nettyReq);
            startVirtualThread(ctx, nettyReq, body);
            return;
        }

        ctx.fireChannelRead(msg);
    }

    private void startVirtualThread(ChannelHandlerContext ctx, FullHttpRequest nettyReq, NettyRequestHttpBody body) {
        try {
            nettyReq.retain();
            Thread thread = virtualThreadFactory.newThread(() -> {
                try {
                    log.debug("Virtual thread started successfully for request: {}", nettyReq.uri());
                    dispatch(ctx, nettyReq, body);
                } catch (Throwable t) {
                    log.error("CRITICAL: Error inside Kora dispatch pipeline (Loom Thread)", t);
                    ctx.executor().execute(ctx::close);
                }
            });

            thread.start();
            log.debug("Virtual thread.start() invoked");
        } catch (Throwable t) {
            log.error("CRITICAL: Failed to create or start virtual thread entirely", t);
            safeRelease(nettyReq);
            ctx.close();
        }
    }

    private void dispatch(ChannelHandlerContext ctx, HttpRequest nettyReq, NettyRequestHttpBody streamingBody) {
        var koraReq = new NettyUnroutedHttpRequest(nettyReq, streamingBody);

        var invocation = currentRouter.route(koraReq);
        var httpServerRequest = invocation.routedRequest();
        var observation = telemetry.observe(httpServerRequest);
        var httpServerRequestObserved = observation.observeRequest(httpServerRequest);
        var rootCtx = PROPAGATOR.extract(Context.root(), nettyReq.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        var activeCtx = rootCtx.with(observation.span());

        var finalContext = KoraContext.empty()
            .with(NETTY_KEY, new NettyContext(nettyReq, ctx))
            .with(MDC_KEY, new MDC())
            .with(OPENTELEMETRY_KEY, activeCtx)
            .with(OBSERVATION_KEY, observation);

        KoraContext.runWith(finalContext, new ActionSupplier(this, ctx, invocation, httpServerRequestObserved, observation, nettyReq, activeCtx));
    }

    private void sendStaticErrorAndClose(ChannelHandlerContext ctx, HttpRequest nettyReq) {
        try {
            var bytes = "Server is shutting down".getBytes(UTF_8);
            var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);
            var errorResp = new DefaultFullHttpResponse(nettyReq.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
            ctx.writeAndFlush(errorResp).addListener(ChannelFutureListener.CLOSE);
        } finally {
            release(nettyReq);
        }
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
        log.error("Exception caught in Netty channel handler pipeline", cause);
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    private static final class ActionSupplier implements Supplier<Void> {
        private final KoraVirtualThreadHandler handler;
        private final ChannelHandlerContext ctx;
        private final HttpServerInvocation invocation;
        private final HttpServerRequest httpServerRequest;
        private final HttpServerObservation observation;
        private final HttpRequest nettyReq;
        private final Context rootCtx;

        public ActionSupplier(KoraVirtualThreadHandler handler, ChannelHandlerContext ctx,
                              HttpServerInvocation invocation, HttpServerRequest httpServerRequest,
                              HttpServerObservation observation, HttpRequest nettyReq, Context rootCtx) {
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
            HttpBodyOutput body = null;
            try {
                response = invocation.proceed(httpServerRequestObserved);
                if (observation != null) {
                    observation.observeResponse(response);
                }
                body = response.body();
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
            } catch (Throwable e) {
                if (e instanceof HttpServerResponse resp) {
                    response = resp;
                    if (observation != null) {
                        observation.observeError(e);
                        observation.observeResponse(response);
                    }
                } else {
                    log.error("Internal server error during request dispatching", e);
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

            try {
                handler.prepareAndSendResponse(ctx, nettyReq, nettyReq.protocolVersion(), HttpUtil.isKeepAlive(nettyReq), response, observation);
            } catch (Throwable t) {
                log.error("Failed to prepare or send HTTP response", t);
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

    private void prepareAndSendResponse(ChannelHandlerContext ctx, HttpRequest nettyReq, HttpVersion version,
                                        boolean keepAlive, HttpServerResponse response, HttpServerObservation observation) {
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

        ctx.executor().execute(() -> {
            try {
                writeStreamBody(ctx, body, nettyReq, version, keepAlive, observation);
            } catch (Throwable t) {
                log.error("Failed to stream response body via EventLoop", t);
                ctx.close();
            }
        });
    }

    private void writeStreamBody(ChannelHandlerContext ctx, HttpBodyOutput body, HttpRequest nettyReq,
                                 HttpVersion version, boolean keepAlive, HttpServerObservation observation) {
        if (!ctx.channel().isActive()) {
            cleanUpResponse(ctx, body, nettyReq, observation);
            return;
        }

        if (ctx.channel().isWritable()) {
            ByteBuf buffer = ctx.alloc().buffer(8192);
            try (var os = new ByteBufOutputStream(buffer)) {
                body.write(os);

                if (buffer.isReadable()) {
                    ctx.writeAndFlush(new DefaultHttpContent(buffer)).addListener(future -> {
                        if (future.isSuccess()) {
                            writeStreamBody(ctx, body, nettyReq, version, keepAlive, observation);
                        } else {
                            buffer.release();
                            ctx.close();
                            cleanUpResponse(ctx, body, nettyReq, observation);
                        }
                    });
                } else {
                    buffer.release();
                    var lastChunk = LastHttpContent.EMPTY_LAST_CONTENT;
                    finalizeAndWrite(ctx, nettyReq, lastChunk, version, keepAlive, body, observation);
                }
            } catch (Throwable t) {
                buffer.release();
                log.error("Error during streaming payload compilation", t);
                ctx.close();
                cleanUpResponse(ctx, body, nettyReq, observation);
            }
        } else {
            ctx.executor().schedule(() -> writeStreamBody(ctx, body, nettyReq, version, keepAlive, observation), 1, TimeUnit.MILLISECONDS);
        }
    }

    private void cleanUpResponse(ChannelHandlerContext ctx, HttpBodyOutput body, HttpRequest nettyReq, HttpServerObservation observation) {
        ctx.channel().attr(ACTIVE_BODY_KEY).set(null);
        if (body != null) {
            try {body.close();} catch (IOException e) {log.error("Failed to close body", e);}
        }
        if (observation != null) {
            observation.end();
        }
        safeRelease(nettyReq);
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, HttpRequest nettyReq, Object msg,
                                  HttpVersion version, boolean keepAlive, @Nullable HttpBodyOutput body,
                                  @Nullable HttpServerObservation observation) {
        if (msg instanceof HttpMessage httpMsg) {
            setupConnectionHeader(httpMsg, version, keepAlive);
        }

        ctx.writeAndFlush(msg).addListener(future -> {
            if (body != null) {
                try { body.close(); } catch (IOException e) { log.error("Failed to close response body", e); }
            }
            if (observation != null) {
                observation.end();
            }

            safeRelease(nettyReq);

            if (future.isSuccess()) {
                log.debug("HTTP response successfully flushed to network for request: {}", nettyReq.uri());
            } else {
                Throwable cause = future.cause();
                if (cause instanceof IOException) {
                    log.debug("Client closed connection prematurely for request: {} (Message: {})", nettyReq.uri(), cause.getMessage());
                } else {
                    log.error("Failed to flush HTTP response to network for request: {}", nettyReq.uri(), cause);
                }
            }

            if (!future.isSuccess() || !keepAlive) {
                ctx.close();
            } else {
                ctx.read();
            }
        });
    }

    void writeHeaders(io.netty.handler.codec.http.HttpHeaders nettyHeaders, HttpHeaders koraHeaders, @Nullable String contentType) {
        if (httpServerConfig.get().headerServerNameEnabled()) {
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

    public void shutdown() {
        shuttingDown = true;
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
}
