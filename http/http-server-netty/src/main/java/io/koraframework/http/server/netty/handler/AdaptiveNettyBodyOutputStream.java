package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.HttpResultCode;
import io.koraframework.http.common.body.HttpBodyOutput;
import io.koraframework.http.server.common.telemetry.HttpServerObservation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

final class AdaptiveNettyBodyOutputStream extends OutputStream {

    private static final int MAX_BUFFER_SIZE = 64 * 1024;
    private static final int CHUNK_SIZE = 16 * 1024;

    private final ChannelHandlerContext ctx;
    private final HttpServerObservation observation;
    private final HttpBodyOutput body;
    private final boolean keepAlive;
    private final HttpResponse responseHeader;

    private CompositeByteBuf buffer;
    private boolean isStreamMode = false;

    public AdaptiveNettyBodyOutputStream(ChannelHandlerContext ctx, HttpResponse responseHeader, boolean keepAlive, HttpServerObservation observation, HttpBodyOutput body) {
        this.ctx = ctx;
        this.responseHeader = responseHeader;
        this.keepAlive = keepAlive;
        this.observation = observation;
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

        // Если длина ответа не была жестко прописана — выставляем CHUNKED
        if (!responseHeader.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            responseHeader.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            responseHeader.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }

        ensureWritability();

        // Отправляем стартовые заголовки ответа и первый накопленный большой кусок
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

            ctx.writeAndFlush(fullResponse).addListener(future -> finalizeConnection(future.isSuccess(), null));
        } else {
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(future -> finalizeConnection(future.isSuccess(), future.cause()));
        }
    }

    private void finalizeConnection(boolean success, Throwable cause) {
        KoraHttpServerHandler.closeBody(observation, body);
        if (success) {
            observation.end();
            if (!keepAlive) {
                ctx.close();
            }
        } else {
            observation.observeResultCode(HttpResultCode.CONNECTION_ERROR);
            if (cause != null) {
                observation.observeError(cause);
            }
            observation.end();
            ctx.close();
        }
    }

    @Override
    public void close() {
        if (buffer != null) {
            ReferenceCountUtil.safeRelease(buffer);
            buffer = null;
        }
    }
}
