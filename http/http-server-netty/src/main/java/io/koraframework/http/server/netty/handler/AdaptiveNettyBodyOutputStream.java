package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.HttpResultCode;
import io.koraframework.http.common.body.HttpBodyOutput;
import io.koraframework.http.server.common.telemetry.HttpServerObservation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

final class AdaptiveNettyBodyOutputStream extends OutputStream {

    private final ChannelHandlerContext ctx;
    private final HttpServerObservation observation;
    private final HttpBodyOutput body;
    private final boolean keepAlive;
    private boolean headerWritten = false;
    private final HttpResponse responseHeader;

    private static final int CHUNK_SIZE = 16 * 1024;

    public AdaptiveNettyBodyOutputStream(ChannelHandlerContext ctx, HttpResponse responseHeader, boolean keepAlive, HttpServerObservation observation, HttpBodyOutput body) {
        this.ctx = ctx;
        this.responseHeader = responseHeader;
        this.keepAlive = keepAlive;
        this.observation = observation;
        this.body = body;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }

        int written = 0;
        while (written < len) {
            ensureWritability();

            int chunkLen = Math.min(len - written, CHUNK_SIZE);
            ByteBuf chunk = ctx.alloc().directBuffer(chunkLen);
            try {
                chunk.writeBytes(b, off + written, chunkLen);

                if (!headerWritten) {
                    ctx.executor().execute(() -> {
                        ctx.write(responseHeader);
                        ctx.writeAndFlush(new DefaultHttpContent(chunk));
                    });
                    headerWritten = true;
                } else {
                    ctx.writeAndFlush(new DefaultHttpContent(chunk));
                }
                written += chunkLen;
            } catch (Throwable t) {
                chunk.release();
                throw new IOException("Failed to write chunk to Netty pipeline", t);
            }
        }
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
                LockSupport.parkNanos(500_000);
            }
            spins++;
        }
    }

    public void complete() {
        ctx.executor().execute(() -> {
            if (!headerWritten) {
                ctx.write(responseHeader);
            }
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
                KoraHttpServerHandler.closeBody(observation, body);
                if (future.isSuccess()) {
                    observation.end();
                    if (!keepAlive) {
                        ctx.close();
                    }
                } else {
                    observation.observeResultCode(HttpResultCode.CONNECTION_ERROR);
                    observation.end();
                    ctx.close();
                }
            });
        });
    }
}
