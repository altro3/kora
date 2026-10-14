package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final FullHttpRequest nettyRequest;
    private final @Nullable ChannelHandlerContext ctx;
    private final long maxRequestBodySize;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private @Nullable volatile InputStream inputStream;
    private boolean released = false;
    private boolean streamClosed = false;
    private ByteBuf currentBuf = null;
    private java.io.IOException pendingException = null;

    public NettyRequestHttpBody(FullHttpRequest nettyRequest, @Nullable ChannelHandlerContext ctx, long maxRequestBodySize) {
        this.nettyRequest = nettyRequest;
        this.ctx = ctx;
        this.maxRequestBodySize = maxRequestBodySize;
    }

    public void prepare() {
        nettyRequest.retain();
    }

    public void addContent(HttpContent content) {
        ByteBuf data = content.content().retain();
        if (data.isReadable()) {
            queue.add(data);
        } else {
            data.release();
        }
        if (content instanceof LastHttpContent) {
            queue.add(LastHttpContent.EMPTY_LAST_CONTENT);
        }
    }

    public void setException(java.io.IOException ex) {
        this.pendingException = ex;
        queue.add(ex);
    }

    @Override
    public long contentLength() {
        return HttpUtil.getContentLength(nettyRequest, -1L);
    }

    @Nullable
    @Override
    public String contentType() {
        return nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    @NonNull
    public InputStream asInputStream() {
        var in = this.inputStream;
        if (in != null) {
            return in;
        }

        InputStream newIn;
        if (ctx == null) {
            var duplicate = nettyRequest.content().retainedDuplicate();
            newIn = new ByteBufInputStream(duplicate, true);
        } else {
            newIn = createStreamingInputStream();
        }

        if (this.inputStream == null) {
            this.inputStream = newIn;
            return newIn;
        }

        if (ctx == null) {
            nettyRequest.content().release();
        }
        var winnerIn = this.inputStream;
        return winnerIn != null ? winnerIn : newIn;
    }

    private InputStream createStreamingInputStream() {
        return new InputStream() {
            private long bytesRead = 0;

            @Override
            public int read() throws IOException {
                byte[] b = new byte[1];
                int r = read(b, 0, 1);
                return r == -1 ? -1 : (b[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (pendingException != null) throw pendingException;
                if (streamClosed) return -1;

                if (currentBuf == null || !currentBuf.isReadable()) {
                    if (currentBuf != null) {
                        currentBuf.release();
                        currentBuf = null;
                    }

                    ctx.channel().config().setAutoRead(true);

                    Object item;
                    try {
                        item = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for request body", e);
                    }

                    if (item instanceof java.io.IOException ex) {
                        throw ex;
                    }
                    if (item == LastHttpContent.EMPTY_LAST_CONTENT) {
                        streamClosed = true;
                        return -1;
                    }
                    currentBuf = (ByteBuf) item;
                }

                int available = currentBuf.readableBytes();
                int bytesToRead = Math.min(len, available);

                if (bytesRead + bytesToRead > maxRequestBodySize) {
                    streamClosed = true;
                    ctx.close();
                    throw new TooLongFrameException("HTTP request body exceeded maximum configured size of " + maxRequestBodySize + " bytes");
                }

                currentBuf.readBytes(b, off, bytesToRead);
                bytesRead += bytesToRead;
                return bytesToRead;
            }

            @Override
            public void close() throws IOException {
                streamClosed = true;
                if (currentBuf != null) {
                    currentBuf.release();
                    currentBuf = null;
                }
                Object item;
                while ((item = queue.poll()) != null) {
                    if (item instanceof ByteBuf buf) {
                        buf.release();
                    }
                }
            }
        };
    }

    @Override
    public void close() throws IOException {
        if (released) {
            return;
        }
        released = true;

        var in = this.inputStream;
        if (in != null) {
            in.close();
        }
        if (currentBuf != null) {
            currentBuf.release();
            currentBuf = null;
        }
        Object item;
        while ((item = queue.poll()) != null) {
            if (item instanceof ByteBuf buf) {
                buf.release();
            }
        }
        nettyRequest.release();
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
