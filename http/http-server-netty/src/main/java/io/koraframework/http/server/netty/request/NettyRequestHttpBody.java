package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.io.*;
import java.util.concurrent.*;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final FullHttpRequest nettyRequest;
    private final @Nullable ChannelHandlerContext ctx;
    private final long maxRequestBodySize;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

    private volatile @Nullable InputStream inputStream;
    private boolean released = false;
    private boolean streamClosed = false;
    private ByteBuf currentBuf = null;
    private IOException pendingException = null;

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

    public void setException(IOException ex) {
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
        return this.inputStream;
    }

    private InputStream createStreamingInputStream() {
        return new InputStream() {
            private long bytesRead = 0;

            @Override
            public int read() throws IOException {
                if (pendingException != null) throw pendingException;
                if (streamClosed) return -1;

                if (currentBuf == null || !currentBuf.isReadable()) {
                    if (!ensureBuffer()) {
                        return -1;
                    }
                }

                if (bytesRead + 1 > maxRequestBodySize) {
                    throwMaxRequestBodySizeException();
                }

                bytesRead++;
                return currentBuf.readByte() & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (pendingException != null) throw pendingException;
                if (streamClosed) return -1;

                if (currentBuf == null || !currentBuf.isReadable()) {
                    if (!ensureBuffer()) {
                        return -1;
                    }
                }

                int available = currentBuf.readableBytes();
                int bytesToRead = Math.min(len, available);

                if (bytesRead + bytesToRead > maxRequestBodySize) {
                    throwMaxRequestBodySizeException();
                }

                currentBuf.readBytes(b, off, bytesToRead);
                bytesRead += bytesToRead;
                return bytesToRead;
            }

            private boolean ensureBuffer() throws IOException {
                if (currentBuf != null) {
                    currentBuf.release();
                    currentBuf = null;
                }

                Object item;
                try {
                    item = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Reading interrupted", e);
                }

                if (item instanceof IOException ex) {
                    throw ex;
                }
                if (item == LastHttpContent.EMPTY_LAST_CONTENT) {
                    streamClosed = true;
                    return false;
                }

                currentBuf = (ByteBuf) item;
                return true;
            }

            private void throwMaxRequestBodySizeException() {
                streamClosed = true;
                if (ctx != null) ctx.close();
                throw new TooLongFrameException("HTTP request body exceeded maximum configured size of " + maxRequestBodySize + " bytes");
            }

            @Override
            public void close() {
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
