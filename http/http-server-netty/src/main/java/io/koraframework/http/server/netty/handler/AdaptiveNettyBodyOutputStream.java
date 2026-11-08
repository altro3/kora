package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.common.telemetry.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

final class AdaptiveNettyBodyOutputStream extends OutputStream {

    private static final int MAX_BUFFER_SIZE = 64 * 1024;

    private final ChannelHandlerContext ctx;
    private final HttpBodyOutput body;
    private final boolean keepAlive;
    private final HttpResponse responseHeader;
    private final FullHttpRequest nettyReq;
    private final HttpServerObservation observation;

    private CompositeByteBuf buffer;
    private boolean isStreamMode = false;
    private long writtenBytes = 0;

    public AdaptiveNettyBodyOutputStream(ChannelHandlerContext ctx, HttpResponse responseHeader, boolean keepAlive, HttpBodyOutput body, FullHttpRequest nettyReq, HttpServerObservation observation) {
        this.ctx = ctx;
        this.responseHeader = responseHeader;
        this.keepAlive = keepAlive;
        this.body = body;
        this.nettyReq = nettyReq;
        this.observation = observation;
        this.buffer = ctx.alloc().compositeBuffer();
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    private void switchToStreamMode() throws IOException {
        isStreamMode = true;
        if (!responseHeader.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            responseHeader.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            responseHeader.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }
        ensureWritability();

        ctx.channel().write(responseHeader);
        if (buffer.isReadable()) {
            try {
                ctx.channel().writeAndFlush(new DefaultHttpContent(buffer)).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Streaming interrupted during initial flush", e);
            } catch (Exception e) {
                throw new IOException("Failed to flush initial buffer", e);
            }
        } else {
            ctx.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            buffer.release();
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

        var latch = new CountDownLatch(1);
        var writabilityHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext context) throws Exception {
                if (context.channel().isWritable()) {
                    context.pipeline().remove(this);
                    latch.countDown();
                }
                context.fireChannelWritabilityChanged();
            }

            @Override
            public void channelInactive(ChannelHandlerContext context) throws Exception {
                context.pipeline().remove(this);
                latch.countDown();
                context.fireChannelInactive();
            }
        };

        ctx.pipeline().addBefore(ctx.name(), "writability-handler", writabilityHandler);
        if (ctx.channel().isWritable()) {
            try {
                ctx.pipeline().remove(writabilityHandler);
            } catch (NoSuchElementException ignored) {}
            return;
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Streaming interrupted during backpressure wait", e);
        }

        if (!ctx.channel().isActive()) {
            throw new IOException("Channel became inactive during backpressure");
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
            ByteBuf chunk = ctx.alloc().directBuffer(len).writeBytes(b, off, len);
            buffer.addComponent(true, chunk);

            if (buffer.readableBytes() > MAX_BUFFER_SIZE) {
                switchToStreamMode();
            }
        } else {
            ensureWritability();

            ByteBuf chunk = ctx.alloc().directBuffer(len).writeBytes(b, off, len);
            try {
                ctx.channel().writeAndFlush(new DefaultHttpContent(chunk)).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Streaming interrupted during write", e);
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
                ctx.channel().writeAndFlush(fullResponse).sync();
                finalizeConnection(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during final response flush", e);
            } catch (Exception e) {
                finalizeConnection(false);
                throw new IOException("Failed to send final response", e);
            }
            return;
        }

        if (isStreamMode) {
            try {
                ctx.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).sync();
                finalizeConnection(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during EMPTY_LAST_CONTENT flush", e);
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
