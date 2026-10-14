package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.body.*;
import io.koraframework.http.server.netty.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.*;

import static io.netty.util.ReferenceCountUtil.*;

final class AdaptiveNettyBodyOutputStream extends OutputStream {

    private static final int MAX_BUFFER_SIZE = 64 * 1024;
    private static final int CHUNK_SIZE = 16 * 1024;

    private final ChannelHandlerContext ctx;
    private final HttpBodyOutput body;
    private final boolean keepAlive;
    private final HttpResponse responseHeader;

    private CompositeByteBuf buffer;
    private boolean isStreamMode = false;

    public AdaptiveNettyBodyOutputStream(ChannelHandlerContext ctx, HttpResponse responseHeader, boolean keepAlive, HttpBodyOutput body) {
        this.ctx = ctx;
        this.responseHeader = responseHeader;
        this.keepAlive = keepAlive;
        this.body = body;
        this.buffer = ctx.alloc().compositeBuffer();
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {return;}

        if (!isStreamMode) {
            ByteBuf chunk = ctx.alloc().directBuffer(len).writeBytes(b, off, len);
            buffer.addComponent(true, chunk);

            if (buffer.readableBytes() > MAX_BUFFER_SIZE) {
                switchToStreamMode();
            }
        } else {
            int written = 0;
            while (written < len) {
                ensureWritability();

                int chunkLen = Math.min(len - written, CHUNK_SIZE);
                ByteBuf chunk = ctx.alloc().directBuffer(chunkLen).writeBytes(b, off + written, chunkLen);

                ctx.writeAndFlush(new DefaultHttpContent(chunk));
                written += chunkLen;
            }
        }
    }

    private void switchToStreamMode() throws IOException {
        isStreamMode = true;
        if (!responseHeader.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            responseHeader.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            responseHeader.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }
        ensureWritability();

        ctx.write(responseHeader);
        if (buffer.isReadable()) {
            ctx.writeAndFlush(new DefaultHttpContent(buffer));
        } else {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            buffer.release();
        }
        buffer = null;
    }

    private void ensureWritability() throws IOException {
        if (!ctx.channel().isActive()) {
            throw new IOException("Connection closed by peer during streaming");
        }

        int spins = 0;
        while (!ctx.channel().isWritable()) {
            if (!ctx.channel().isActive()) {
                throw new IOException("Connection severed during backpressure wait");
            }
            if (spins < 10) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(200_000);
            }
            spins++;
        }
    }

    public void complete() {
        if (!isStreamMode) {
            int finalSize = buffer.readableBytes();
            responseHeader.headers().set(HttpHeaderNames.CONTENT_LENGTH, finalSize);
            responseHeader.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);

            var fullResponse = new DefaultFullHttpResponse(
                responseHeader.protocolVersion(),
                responseHeader.status(),
                buffer
            );
            fullResponse.headers().set(responseHeader.headers());

            ctx.writeAndFlush(fullResponse).addListener(future -> finalizeConnection(future.isSuccess()));
        } else {
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(future -> finalizeConnection(future.isSuccess()));
        }
    }

    private void finalizeConnection(boolean success) {
        if (buffer != null) {
            safeRelease(buffer);
            buffer = null;
        }
        if (body != null) {
            try {
                body.close();
            } catch (IOException e) {
                //
            }
        }

        var nettyContext = NettyContext.get();
        if (nettyContext != null) {
            safeRelease(nettyContext.request());
        }

        if (success) {
            if (!keepAlive) {
                ctx.close();
            }
        } else {
            ctx.close();
        }
    }

    public boolean isStreamMode() {
        return isStreamMode;
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
            safeRelease(buffer);
            buffer = null;
        }
    }
}
