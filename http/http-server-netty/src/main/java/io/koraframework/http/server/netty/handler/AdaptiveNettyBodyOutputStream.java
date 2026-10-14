package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.telemetry.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

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

        ctx.write(responseHeader);
        if (buffer.isReadable()) {
            var flushFuture = new java.util.concurrent.CompletableFuture<Void>();
            ctx.writeAndFlush(new DefaultHttpContent(buffer)).addListener(f -> {
                if (f.isSuccess()) flushFuture.complete(null);
                else flushFuture.completeExceptionally(f.cause());
            });
            try {
                flushFuture.get();
            } catch (Exception e) {
                throw new IOException("Failed to flush initial buffer", e);
            }
        } else {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
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

        var writabilityFuture = new CompletableFuture<Void>();
        ctx.pipeline().addBefore(ctx.name(), "writability-handler", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext context) throws Exception {
                if (context.channel().isWritable()) {
                    writabilityFuture.complete(null);
                    context.pipeline().remove(this);
                }
                context.fireChannelWritabilityChanged();
            }

            @Override
            public void channelInactive(ChannelHandlerContext context) throws Exception {
                writabilityFuture.completeExceptionally(new IOException("Channel became inactive during backpressure"));
                context.pipeline().remove(this);
                context.fireChannelInactive();
            }
        });

        if (ctx.channel().isWritable()) {
            try {
                ctx.pipeline().remove("writability-handler");
            } catch (NoSuchElementException ignored) {}
            return;
        }

        try {
            writabilityFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Streaming interrupted during backpressure wait", e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
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
                var errorResponse = io.koraframework.http.server.common.response.HttpServerResponse.of(500);
                observation.observeError(t);
                observation.observeResponse(errorResponse);
                observation.end();
            }
            throw t; // Пробрасываем наверх хендлеру для закрытия сокета
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

            var writeFuture = new CompletableFuture<Void>();
            ctx.writeAndFlush(new DefaultHttpContent(chunk)).addListener(future -> {
                if (future.isSuccess()) {
                    writeFuture.complete(null);
                } else {
                    writeFuture.completeExceptionally(future.cause());
                }
            });

            try {
                writeFuture.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Streaming interrupted during write", e);
            } catch (ExecutionException e) {
                throw new IOException("Connection broken during streaming", e.getCause());
            }
        }
        this.writtenBytes += len;
    }

    public void complete() {
        ctx.executor().execute(() -> {
            if (this.buffer != null) {
                int finalSize = buffer.readableBytes();
                String initialContentLengthStr = responseHeader.headers().get(HttpHeaderNames.CONTENT_LENGTH);

                if (initialContentLengthStr != null) {
                    try {
                        long expectedSize = Long.parseLong(initialContentLengthStr);
                        if (expectedSize != finalSize) {
                            ReferenceCountUtil.safeRelease(buffer);
                            buffer = null;
                            if (observation != null) {
                                observation.observeError(new IOException("Response body truncated: expected " + expectedSize + " bytes, but wrote " + finalSize));
                            }
                            finalizeConnection(false);
                            return;
                        }
                    } catch (NumberFormatException ignored) {}
                }

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
                String initialContentLengthStr = responseHeader.headers().get(HttpHeaderNames.CONTENT_LENGTH);
                boolean isTruncated = false;
                if (initialContentLengthStr != null) {
                    try {
                        long expectedSize = Long.parseLong(initialContentLengthStr);
                        if (this.writtenBytes < expectedSize) {
                            isTruncated = true;
                        }
                    } catch (NumberFormatException ignored) {}
                }

                if (isTruncated) {
                    if (observation != null) {
                        observation.observeError(new IOException("Response stream truncated: expected " + initialContentLengthStr + " bytes, but wrote " + this.writtenBytes));
                    }
                    finalizeConnection(false);
                } else {
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(future -> finalizeConnection(future.isSuccess()));
                }
            }
        });
    }

    private void triggerFinalize(boolean success) {
        if (observation != null) {
            observation.end();
        }
        finalizeConnection(success);
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
