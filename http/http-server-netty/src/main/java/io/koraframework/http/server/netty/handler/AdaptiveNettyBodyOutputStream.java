package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.telemetry.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.*;

final class AdaptiveNettyBodyOutputStream extends OutputStream {

    private static final int INITIAL_BUFFER_SIZE = 4096;
    private static final int MAX_BUFFER_SIZE = 64 * 1024;

    private final ChannelHandlerContext ctx;
    private final HttpBodyOutput body;
    private final boolean keepAlive;
    private final HttpResponse responseHeader;
    private final FullHttpRequest nettyReq;
    private final HttpServerObservation observation;

    private ByteBuf buffer;
    private boolean isStreamMode = false;
    private long writtenBytes = 0;
    private volatile Thread waitingThread = null;

    public AdaptiveNettyBodyOutputStream(ChannelHandlerContext ctx, HttpResponse responseHeader, boolean keepAlive, HttpBodyOutput body, FullHttpRequest nettyReq, HttpServerObservation observation) {
        this.ctx = ctx;
        this.responseHeader = responseHeader;
        this.keepAlive = keepAlive;
        this.body = body;
        this.nettyReq = nettyReq;
        this.observation = observation;
        this.buffer = ctx.alloc().directBuffer(INITIAL_BUFFER_SIZE, MAX_BUFFER_SIZE + 8192);
    }

    private static void awaitNonBlocking(io.netty.util.concurrent.Future<?> nettyFuture) throws IOException {
        if (nettyFuture.isDone()) {
            if (!nettyFuture.isSuccess()) throw new IOException(nettyFuture.cause());
            return;
        }
        var currentThread = Thread.currentThread();
        nettyFuture.addListener(_ -> LockSupport.unpark(currentThread));
        while (!nettyFuture.isDone()) {
            LockSupport.park();
        }
        if (!nettyFuture.isSuccess()) {
            throw new IOException(nettyFuture.cause());
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (buffer == null && isStreamMode) {
            throw new IOException("Stream closed");
        }

        if (!isStreamMode) {
            buffer.writeByte(b);
            if (buffer.readableBytes() > MAX_BUFFER_SIZE) {
                switchToStreamMode();
            }
        } else {
            ensureWritability();
            ByteBuf chunk = ctx.alloc().directBuffer(1).writeByte(b);
            try {
                awaitNonBlocking(ctx.writeAndFlush(new DefaultHttpContent(chunk)));
            } catch (Exception e) {
                throw new IOException("Connection broken during streaming", e);
            }
        }
        this.writtenBytes++;
    }

    private void switchToStreamMode() throws IOException {
        isStreamMode = true;
        if (!responseHeader.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            responseHeader.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            responseHeader.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }
        ensureWritability();

        ctx.write(responseHeader);
        if (buffer != null && buffer.isReadable()) {
            try {
                awaitNonBlocking(ctx.writeAndFlush(new DefaultHttpContent(buffer)));
            } catch (Exception e) {
                throw new IOException("Failed to flush initial buffer", e);
            }
        } else {
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            if (buffer != null) buffer.release();
        }
        buffer = null;
    }

    private void ensureWritability() throws IOException {
        if (!ctx.channel().isActive()) {
            throw new IOException("Connection closed by peer");
        }

        if (ctx.channel().isWritable()) {
            return;
        }

        this.waitingThread = Thread.currentThread();
        try {
            while (!ctx.channel().isWritable() && ctx.channel().isActive()) {
                LockSupport.parkNanos(1_000_000);
            }
        } finally {
            this.waitingThread = null;
        }

        if (!ctx.channel().isActive()) {
            throw new IOException("Channel became inactive during backpressure");
        }
    }

    public void wakeup() {
        Thread thread = this.waitingThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    public void writeBody() throws Throwable {
        try {
            body.write(this);
            complete();
            if (observation != null) {
                observation.end();
            }
        } catch (Throwable t) {
            if (observation != null) {
                var errorResponse = HttpServerResponse.of(500);
                observation.observeError(t);
                observation.observeResponse(errorResponse);
                observation.end();
            }
            throw t;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len <= 0) return;

        if (!isStreamMode) {
            buffer.writeBytes(b, off, len);

            if (buffer.readableBytes() > MAX_BUFFER_SIZE) {
                switchToStreamMode();
            }
        } else {
            ensureWritability();

            ByteBuf chunk = ctx.alloc().directBuffer(len).writeBytes(b, off, len);
            try {
                awaitNonBlocking(ctx.writeAndFlush(new DefaultHttpContent(chunk)));
            } catch (Exception e) {
                throw new IOException("Connection broken during streaming", e);
            }
        }
        this.writtenBytes += len;
    }

    public void complete() throws IOException {
        if (!isStreamMode && buffer != null) {
            int finalSize = buffer.readableBytes();

            var fullResponse = new DefaultFullHttpResponse(
                responseHeader.protocolVersion(),
                responseHeader.status(),
                buffer
            );

            var nettyHeaders = fullResponse.headers();
            for (var header : responseHeader.headers()) {
                String key = header.getKey();
                if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(key)
                    || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(key)) {
                    continue;
                }
                nettyHeaders.add(key, header.getValue());
            }

            nettyHeaders.set(HttpHeaderNames.CONTENT_LENGTH, finalSize);

            buffer = null;
            try {
                awaitNonBlocking(ctx.writeAndFlush(fullResponse));
                finalizeConnection(true);
            } catch (Exception e) {
                finalizeConnection(false);
                throw new IOException("Failed to send final response", e);
            }
            return;
        }

        if (isStreamMode) {
            try {
                awaitNonBlocking(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT));
                finalizeConnection(true);
            } catch (Exception e) {
                finalizeConnection(false);
                throw new IOException("Failed to flush EMPTY_LAST_CONTENT", e);
            }
        }
    }

    private void finalizeConnection(boolean success) {
        if (buffer != null) {
            if (!success) {
                ReferenceCountUtil.safeRelease(buffer);
            }
            buffer = null;
        }
        if (!success && body != null) {
            try {
                body.close();
            } catch (IOException e) { /* ignore */ }
        }

        if (nettyReq != null) {
            ReferenceCountUtil.safeRelease(nettyReq);
        }

        if (success && keepAlive) {
            ctx.channel().config().setAutoRead(true);
        } else {
            ctx.close();
        }
    }

    public boolean isStreamMode() {
        return isStreamMode;
    }

    public long writtenBytes() {
        return this.writtenBytes;
    }

    @Override
    public void flush() throws IOException {
        if (!isStreamMode) {
            switchToStreamMode();
        }
        ctx.flush();
    }

    @Override
    public void close() {
        if (buffer != null) {
            ReferenceCountUtil.safeRelease(buffer);
            buffer = null;
        }
    }
}
