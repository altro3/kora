package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.body.*;
import io.koraframework.http.server.netty.buffer.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.util.concurrent.*;

public final class KoraOutboundAccess {
    private static final Logger LOG = LoggerFactory.getLogger(KoraOutboundAccess.class);

    private final HttpRequest request;
    private final ChannelHandlerContext ctx;
    private final PipeliningServerHandler parent;

    private boolean skipCompression;
    @Nullable
    private OutboundHandler handler;
    @Nullable
    private Object attachment;
    private boolean closeAfterWrite;
    private volatile boolean finalWriteIssued;
    private volatile boolean recordFinalWrite;
    @Nullable
    private ChannelFuture finalWrite;

    public KoraOutboundAccess(HttpRequest request, ChannelHandlerContext ctx, PipeliningServerHandler parent) {
        this.request = request;
        this.ctx = ctx;
        this.parent = parent;
    }

    public HttpRequest request() {
        return request;
    }

    public void attachment(Object attachment) {
        this.attachment = attachment;
    }

    @Nullable
    public Object attachment() {
        return attachment;
    }

    public Runnable whenAbandoned(Runnable callback) {
        var closeFuture = ctx.channel().closeFuture();
        ChannelFutureListener listener = future -> callback.run();
        closeFuture.addListener(listener);
        return () -> closeFuture.removeListener(listener);
    }

    public boolean isAbandoned() {
        return !ctx.channel().isActive() && finalWritePending();
    }

    private boolean finalWritePending() {
        if (!finalWriteIssued) {
            return true;
        }
        var future = finalWrite;
        return future != null && !future.isSuccess();
    }

    public boolean recordFinalWrite() {
        recordFinalWrite = true;
        return !finalWriteIssued;
    }

    @Nullable
    public ChannelFuture finalWrite() {
        return finalWrite;
    }

    public boolean isRecordFinalWrite() {
        return recordFinalWrite;
    }

    public void setFinalWriteIssued(boolean finalWriteIssued) {
        this.finalWriteIssued = finalWriteIssued;
    }

    public void setFinalWrite(@Nullable ChannelFuture finalWrite) {
        this.finalWrite = finalWrite;
    }

    public void closeAfterWrite() {
        closeAfterWrite = true;
    }

    public void closeAfterWriteInEventLoop() {
        closeAfterWrite = true;
        var current = handler;
        if (current != null) {
            current.markCloseAfterWrite();
        }
    }

    private void preprocess(HttpResponse message) {
        var requestVersion = request.protocolVersion();
        var responseVersion = parent.rejectUnsupportedHttpVersions && !HttpVersion.HTTP_1_0.equals(requestVersion)
            ? HttpVersion.HTTP_1_1 : requestVersion;

        if (!message.protocolVersion().equals(responseVersion)) {
            message.setProtocolVersion(responseVersion);
        }

        if (request.protocolVersion().isKeepAliveDefault()) {
            if (request.headers().contains(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE, true)) {
                closeAfterWrite();
            }
        } else {
            if (!requestsKeepAlive()) {
                closeAfterWrite();
            }
        }

        boolean http10KeptAlive = false;
        if (message.protocolVersion().isKeepAliveDefault()) {
            if (message.headers().contains(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE, true)) {
                closeAfterWrite();
            } else if (closeAfterWrite) {
                message.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
        } else {
            if (!message.headers().contains(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE, true)) {
                if (parent.http10KeepAlive && !closeAfterWrite
                    && (HttpUtil.isContentLengthSet(message) || !parent.canHaveBody(message.status()) || HttpMethod.HEAD.equals(request.method()))
                    && !message.headers().contains(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE, true)) {
                    http10KeptAlive = true;
                    message.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                } else {
                    closeAfterWrite();
                }
            } else if (closeAfterWrite) {
                message.headers().remove(HttpHeaderNames.CONNECTION);
            }
        }

        if (!http10KeptAlive && !HttpMethod.HEAD.equals(request.method()) && !HttpUtil.isContentLengthSet(message) && !HttpUtil.isTransferEncodingChunked(message) && parent.canHaveBody(message.status())) {
            HttpUtil.setKeepAlive(message, false);
            closeAfterWrite();
        }
    }

    private boolean requestsKeepAlive() {
        if (parent.http10KeepAlive) {
            var headers = request.headers();
            return headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE, true)
                && !headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE, true);
        }
        return request.headers().contains(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE, true);
    }

    public void writeContinue() {
        if (handler == null) {
            write(new ContinueOutboundHandler(this, parent));
        }
    }

    public boolean responseStarted() {
        var current = handler;
        return current != null && !(current instanceof ContinueOutboundHandler cont && cont.next == null);
    }

    public void write(OutboundHandler handler) {
        if (responseStarted()) {
            handler.discardRefused();
            throw new IllegalStateException("Only one response per request");
        }

        var eventLoop = ctx.channel().eventLoop();
        if (!eventLoop.inEventLoop()) {
            try {
                eventLoop.execute(() -> writeMoved(handler));
            } catch (Throwable t) {
                handler.discardRefused();
                throw t;
            }
            return;
        }

        if (parent.removed) {
            handler.discardOutbound();
            return;
        }

        if (closeAfterWrite) {
            boolean marked = false;
            try {
                handler.markCloseAfterWrite();
                marked = true;
            } finally {
                if (!marked) {
                    handler.discardRefused();
                }
            }
        }

        if (this.handler instanceof ContinueOutboundHandler cont) {
            cont.next = handler;
            parent.writeSome();
        } else {
            this.handler = handler;
            if (parent.outboundQueue.peek() == this) {
                parent.writeSome();
            }
        }
    }

    private void writeMoved(OutboundHandler handler) {
        if (responseStarted()) {
            LOG.warn("Only one response per request: a response written from another thread is dropped");
            handler.discardRefused();
            return;
        }
        try {
            write(handler);
        } catch (Throwable t) {
            giveUpOffLoopWrite(t);
        }
    }

    public void abort() {
        var eventLoop = ctx.channel().eventLoop();
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::abort);
            return;
        }
        var current = handler instanceof ContinueOutboundHandler cont ? cont.next : handler;
        if (parent.removed || (current != null && current.responseWritten)) {
            return;
        }
        ctx.close();
    }

    public void runInResponseOrder(Runnable action, Runnable discard) {
        write(new ActionOutboundHandler(this, action, discard, parent));
    }

    public void writeHeadResponse(HttpResponse response) {
        writeFull(new DefaultFullHttpResponse(
            response.protocolVersion(),
            response.status(),
            Unpooled.EMPTY_BUFFER,
            response.headers(),
            EmptyHttpHeaders.INSTANCE
        ), true);
    }

    private void writeFull(FullHttpResponse response, boolean headResponse) {
        FullOutboundHandler oh = null;
        try {
            response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            if (parent.canHaveBody(response.status())) {
                if (!headResponse) {
                    HttpUtil.setContentLength(response, response.content().readableBytes());
                }
            } else {
                response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
            }
            preprocess(response);
            oh = new FullOutboundHandler(this, response, parent);
            if (response.content().isReadable()) {
                prepareCompression(response, oh, response.content().readableBytes());
            }
        } catch (Throwable t) {
            response.release();
            discardCompression(oh);
            throw t;
        }
        write(oh);
    }

    private static void discardCompression(@Nullable OutboundHandler handler) {
        if (handler != null && handler.compressionSession != null) {
            handler.compressionSession.discard();
            handler.compressionSession = null;
        }
    }

    public void write(HttpResponse response, @Nullable KoraAvailableBody body) {
        if (body == null) {
            writeFull(new DefaultFullHttpResponse(response.protocolVersion(), response.status(), Unpooled.EMPTY_BUFFER, response.headers(), EmptyHttpHeaders.INSTANCE), false);
            return;
        }
        try {
            writeFull(new DefaultFullHttpResponse(
                response.protocolVersion(),
                response.status(),
                body.toByteBuf(),
                response.headers(),
                EmptyHttpHeaders.INSTANCE
            ), false);
        } catch (Throwable t) {
            body.close();
            throw t;
        }
    }

    public void write(HttpResponse response, Flow.Publisher<KoraReadBuffer> body) {
        var eventLoop = ctx.channel().eventLoop();
        if (!eventLoop.inEventLoop()) {
            try {
                eventLoop.execute(() -> writeClaimedStreaming(response, body));
            } catch (Throwable t) {
                closeBodyQuietly(body);
                throw t;
            }
            return;
        }

        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        if (parent.canHaveBody(response.status()) && !request.protocolVersion().equals(HttpVersion.HTTP_1_0)) {
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        } else {
            response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        }

        preprocess(response);
        var oh = new KoraStreamingOutboundHandler(this, response, parent);
        try {
            prepareCompression(response, oh, -1);
            body.subscribe(oh);
        } catch (Throwable t) {
            discardCompression(oh);
            closeBodyQuietly(body);
            throw t;
        }
        write(oh);
    }

    private void writeClaimedStreaming(HttpResponse response, Flow.Publisher<KoraReadBuffer> body) {
        if (responseStarted()) {
            LOG.warn("Only one response per request: a response written from another thread is dropped");
            closeBodyQuietly(body);
            return;
        }
        try {
            write(response, body);
        } catch (Throwable t) {
            closeBodyQuietly(body);
            giveUpOffLoopWrite(t);
        }
    }

    private static void closeBodyQuietly(Flow.Publisher<KoraReadBuffer> body) {
        if (body instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void writeClaimedStreaming(HttpResponse response, KoraStreamingNettyBody body) {
        if (responseStarted()) {
            LOG.warn("Only one response per request: a response written from another thread is dropped");
            body.close();
            return;
        }
        try {
            write(response, body);
        } catch (Throwable t) {
            body.close();
            giveUpOffLoopWrite(t);
        }
    }

    private void giveUpOffLoopWrite(Throwable t) {
        var current = handler instanceof ContinueOutboundHandler cont ? cont.next : handler;
        if (current != null && current.responseWritten) {
            LOG.warn("Failed to write a response that followed a response written from another thread: {}", t.getMessage(), t);
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        LOG.warn("Failed to write the response: {}", t.getMessage(), t);
        parent.requestHandler.responseFailed(attachment, t);
        if (current != null) {
            if (parent.outboundHandler != handler && parent.outboundHandler != current && !parent.outboundQueue.contains(this)) {
                current.discardOutbound();
            }
            ctx.close();
        } else {
            parent.rejectUnaccepted(this);
        }
    }

    public void write(FullHttpResponse response) {
        var eventLoop = ctx.channel().eventLoop();
        if (!eventLoop.inEventLoop()) {
            try {
                eventLoop.execute(() -> writeClaimedFull(response));
            } catch (Throwable t) {
                response.release();
                throw t;
            }
            return;
        }
        writeFull(response, false);
    }

    private void writeClaimedFull(FullHttpResponse response) {
        if (responseStarted()) {
            LOG.warn("Only one response per request: a response written from another thread is dropped");
            response.release();
            return;
        }
        try {
            writeFull(response, false);
        } catch (Throwable t) {
            response.release();
            giveUpOffLoopWrite(t);
        }
    }

    public void skipCompression() {
        skipCompression = true;
    }

    private void prepareCompression(HttpResponse response, OutboundHandler outboundHandler, long contentLength) {
        if (parent.compressor == null || skipCompression) {
            return;
        }
        var compressionSession = parent.compressor.prepare(ctx, request, response, contentLength);
        if (compressionSession != null) {
            outboundHandler.compressionSession = compressionSession;
            if (!(response instanceof FullHttpResponse) && response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
                response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
        }
    }

    @Nullable
    public OutboundHandler handler() {
        return handler;
    }

    public void setHandler(@Nullable OutboundHandler handler) {
        this.handler = handler;
    }

    public boolean isCloseAfterWrite() {
        return closeAfterWrite;
    }
}
