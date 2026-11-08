package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import io.netty.util.concurrent.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.*;

import static io.netty.util.ReferenceCountUtil.*;

@ChannelHandler.Sharable
public final class NettyResponseWriteHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(NettyResponseWriteHandler.class);
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

    private final ValueOf<HttpServerConfig> httpServerConfig;

    public NettyResponseWriteHandler(ValueOf<HttpServerConfig> httpServerConfig) {
        this.httpServerConfig = httpServerConfig;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ResponseContainer)) {
            ctx.fireChannelRead(msg);
            return;
        }

        var container = (ResponseContainer) msg;
        var nettyReq = container.nettyReq();
        var version = nettyReq.protocolVersion();
        var keepAlive = HttpUtil.isKeepAlive(nettyReq);
        var response = container.koraResponse();
        var observation = container.observation();
        boolean isHead = nettyReq.method() == HttpMethod.HEAD;

        var body = response.body();
        var content = body != null ? body.getFullContentIfAvailable() : null;

        if (body == null || isHead || content != null) {
            if (ctx.executor().inEventLoop()) {
                prepareAndSendResponse(ctx, nettyReq, version, keepAlive, response, isHead, observation);
            } else {
                ctx.executor().execute(new PrepareAndSendRunnable(this, ctx, nettyReq, version, keepAlive, response, isHead, observation));
            }
            return;
        }

        ctx.executor().execute(new StreamExecutionRunnable(this, ctx, container, version, keepAlive, response, body, isHead, observation, nettyReq));
    }

    void prepareAndSendResponse(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpVersion version, boolean keepAlive, HttpServerResponse response, boolean isHead, HttpServerObservation observation) {
        var body = response.body();
        HttpResponseStatus status = HttpResponseStatus.valueOf(response.code());

        if (body == null) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), null);
            nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, null, observation);
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
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, body, observation);
            return;
        }

        try {
            var content = body.getFullContentIfAvailable();
            if (content != null) {
                ByteBuf nettyBody = Unpooled.wrappedBuffer(content);
                var nettyResp = new DefaultFullHttpResponse(version, status, nettyBody);
                writeHeaders(nettyResp.headers(), response.headers(), contentType);
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, nettyBody.readableBytes());
                finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, body, observation);
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

            var output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, body, nettyReq, observation);
            body.write(output);
            output.complete();

        } catch (Throwable e) {
            if (observation != null) {
                observation.observeError(e);
                observation.end();
            }
            closeBody(body);
            safeRelease(nettyReq);
            ctx.close();
        }
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpMessage msg, HttpVersion version, boolean keepAlive, @Nullable HttpBodyOutput body, @Nullable HttpServerObservation observation) {
        setupConnectionHeader(msg, version, keepAlive);

        if (keepAlive) {
            ctx.channel().config().setAutoRead(true);
        }

        ctx.writeAndFlush(msg).addListener(new FinalizeWriteListener(ctx, body, observation, nettyReq, keepAlive));
    }

    void writeHeaders(HttpHeaders nettyHeaders, io.koraframework.http.common.header.HttpHeaders koraHeaders, @Nullable String contentType) {
        if (this.httpServerConfig.get().headerServerNameEnabled()) {
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

    void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
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

    static void closeBody(@Nullable HttpBodyOutput body) {
        if (body != null) {
            try {
                body.close();
            } catch (IOException e) {
                log.error("Failed to close response body", e);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty pipeline exception caught", cause);
        ctx.close();
    }

    private static final class PrepareAndSendRunnable implements Runnable {
        private final NettyResponseWriteHandler handler;
        private final ChannelHandlerContext ctx;
        private final FullHttpRequest nettyReq;
        private final HttpVersion version;
        private final boolean keepAlive;
        private final HttpServerResponse response;
        private final boolean isHead;
        private final HttpServerObservation observation;

        public PrepareAndSendRunnable(NettyResponseWriteHandler handler, ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpVersion version, boolean keepAlive, HttpServerResponse response, boolean isHead, HttpServerObservation observation) {
            this.handler = handler;
            this.ctx = ctx;
            this.nettyReq = nettyReq;
            this.version = version;
            this.keepAlive = keepAlive;
            this.response = response;
            this.isHead = isHead;
            this.observation = observation;
        }

        @Override
        public void run() {
            handler.prepareAndSendResponse(ctx, nettyReq, version, keepAlive, response, isHead, observation);
        }
    }

    private static final class StreamExecutionRunnable implements Runnable {
        private final NettyResponseWriteHandler handler;
        private final ChannelHandlerContext ctx;
        private final ResponseContainer container;
        private final HttpVersion version;
        private final boolean keepAlive;
        private final HttpServerResponse response;
        private final HttpBodyOutput body;
        private final boolean isHead;
        private final HttpServerObservation observation;
        private final FullHttpRequest nettyReq;

        public StreamExecutionRunnable(NettyResponseWriteHandler handler, ChannelHandlerContext ctx, ResponseContainer container, HttpVersion version, boolean keepAlive, HttpServerResponse response, HttpBodyOutput body, boolean isHead, HttpServerObservation observation, FullHttpRequest nettyReq) {
            this.handler = handler;
            this.ctx = ctx;
            this.container = container;
            this.version = version;
            this.keepAlive = keepAlive;
            this.response = response;
            this.body = body;
            this.isHead = isHead;
            this.observation = observation;
            this.nettyReq = nettyReq;
        }

        @Override
        public void run() {
            Thread.startVirtualThread(new AsyncStreamTask(handler, ctx, container, version, keepAlive, response, body, isHead, observation, nettyReq));
        }
    }

    private static final class AsyncStreamTask implements Runnable {
        private final NettyResponseWriteHandler handler;
        private final ChannelHandlerContext ctx;
        private final ResponseContainer container;
        private final HttpVersion version;
        private final boolean keepAlive;
        private final HttpServerResponse response;
        private final HttpBodyOutput body;
        private final boolean isHead;
        private final HttpServerObservation observation;
        private final FullHttpRequest nettyReq;

        public AsyncStreamTask(NettyResponseWriteHandler handler, ChannelHandlerContext ctx, ResponseContainer container, HttpVersion version, boolean keepAlive, HttpServerResponse response, HttpBodyOutput body, boolean isHead, HttpServerObservation observation, FullHttpRequest nettyReq) {
            this.handler = handler;
            this.ctx = ctx;
            this.container = container;
            this.version = version;
            this.keepAlive = keepAlive;
            this.response = response;
            this.body = body;
            this.isHead = isHead;
            this.observation = observation;
            this.nettyReq = nettyReq;
        }

        @Override
        public void run() {
            var status = HttpResponseStatus.valueOf(response.code());
            var nettyResp = new DefaultHttpResponse(version, status);
            handler.writeHeaders(nettyResp.headers(), response.headers(), body.contentType());

            if (body.contentLength() >= 0) {
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.contentLength());
            } else {
                nettyResp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
            handler.setupConnectionHeader(nettyResp, version, keepAlive);

            AdaptiveNettyBodyOutputStream output = null;
            try {
                output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, body, nettyReq, observation);
                output.writeBody();
                output.complete();

                if (nettyReq != null) {
                    safeRelease(nettyReq);
                }

                ctx.executor().execute(new DecrementDispatcherCountersRunnable(ctx));

            } catch (Throwable e) {
                if (output != null) {
                    output.close();
                }
                closeBody(body);
                ctx.executor().execute(new ResetAutoReadRunnable(ctx));

                if (nettyReq != null) {
                    safeRelease(nettyReq);
                }

                if (output != null && !output.isStreamMode()) {
                    ctx.executor().execute(new SendStreamErrorResponseRunnable(handler, ctx, version, keepAlive, e));
                } else {
                    ctx.executor().execute(new CloseChannelRunnable(ctx));
                }
            }
        }
    }

    private static final class FinalizeWriteListener implements GenericFutureListener<ChannelFuture> {
        private final ChannelHandlerContext ctx;
        private final HttpBodyOutput body;
        private final HttpServerObservation observation;
        private final FullHttpRequest nettyReq;
        private final boolean keepAlive;

        public FinalizeWriteListener(ChannelHandlerContext ctx, HttpBodyOutput body, HttpServerObservation observation, FullHttpRequest nettyReq, boolean keepAlive) {
            this.ctx = ctx;
            this.body = body;
            this.observation = observation;
            this.nettyReq = nettyReq;
            this.keepAlive = keepAlive;
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            closeBody(body);

            var dispatcher = future.channel().pipeline().get(KoraVirtualThreadDispatcher.class);
            if (dispatcher != null) {
                dispatcher.getActiveRequests().decrementAndGet();
                dispatcher.getPhaser().arriveAndDeregister();
            }

            if (observation != null) {
                if (!future.isSuccess()) {
                    observation.observeError(future.cause());
                }
                observation.end();
            }

            if (nettyReq != null) {
                safeRelease(nettyReq);
            }

            if (!future.isSuccess() || !keepAlive) {
                ctx.close();
            }
        }
    }

    private static final class DecrementDispatcherCountersRunnable implements Runnable {
        private final ChannelHandlerContext ctx;

        public DecrementDispatcherCountersRunnable(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            var dispatcher = ctx.pipeline().get(KoraVirtualThreadDispatcher.class);
            if (dispatcher != null) {
                dispatcher.getActiveRequests().decrementAndGet();
                dispatcher.getPhaser().arriveAndDeregister();
            }
        }
    }

    private static final class ResetAutoReadRunnable implements Runnable {
        private final ChannelHandlerContext ctx;

        public ResetAutoReadRunnable(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            ctx.channel().config().setAutoRead(true);
        }
    }

    private static final class CloseChannelRunnable implements Runnable {
        private final ChannelHandlerContext ctx;

        public CloseChannelRunnable(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            ctx.close();
        }
    }

    private static final class SendStreamErrorResponseRunnable implements Runnable {
        private final NettyResponseWriteHandler handler;
        private final ChannelHandlerContext ctx;
        private final HttpVersion version;
        private final boolean keepAlive;
        private final Throwable exception;

        public SendStreamErrorResponseRunnable(NettyResponseWriteHandler handler, ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Throwable exception) {
            this.handler = handler;
            this.ctx = ctx;
            this.version = version;
            this.keepAlive = keepAlive;
            this.exception = exception;
        }

        @Override
        public void run() {
            if (ctx.channel().isActive()) {
                var message = exception.getMessage() != null ? exception.getMessage() : "Internal Server Error";
                var bytes = message.getBytes(StandardCharsets.UTF_8);
                var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);

                var errResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
                errResp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
                errResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

                handler.setupConnectionHeader(errResp, version, keepAlive);
                ctx.writeAndFlush(errResp).addListener(new CloseFutureListener(ctx));
            } else {
                ctx.close();
            }
        }
    }

    private static final class CloseFutureListener implements GenericFutureListener<ChannelFuture> {
        private final ChannelHandlerContext ctx;

        public CloseFutureListener(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            ctx.close();
        }
    }
}
