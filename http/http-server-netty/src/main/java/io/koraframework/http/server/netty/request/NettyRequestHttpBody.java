package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.io.*;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final HttpRequest nettyRequest;
    private final ChannelHandlerContext ctx;
    private final long maxRequestBodySize;
    private final CompositeByteBuf bodyBuffer;
    private long bytesRead = 0;
    private boolean streamClosed = false;
    private boolean released = false;

    public NettyRequestHttpBody(HttpRequest nettyRequest, ChannelHandlerContext ctx, long maxRequestBodySize) {
        this.nettyRequest = nettyRequest;
        this.ctx = ctx;
        this.maxRequestBodySize = maxRequestBodySize;
        this.bodyBuffer = ctx.alloc().compositeBuffer();
    }

    public void addContent(HttpContent content) {
        if (streamClosed) {
            return;
        }

        ByteBuf chunk = content.content().retain();
        if (chunk.isReadable()) {
            if (bytesRead + chunk.readableBytes() > maxRequestBodySize) {
                chunk.release();
                setException(new io.netty.handler.codec.TooLongFrameException(
                    "HTTP request body exceeded maximum size of " + maxRequestBodySize + " bytes"
                ));
                return;
            }

            bytesRead += chunk.readableBytes();
            bodyBuffer.addComponent(true, chunk);
        } else {
            chunk.release();
        }

        if (content instanceof LastHttpContent) {
            streamClosed = true;
        }
    }

    public void setException(Throwable ex) {
        streamClosed = true;
        ctx.channel().eventLoop().execute(() -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        });
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
        return new ByteBufInputStream(bodyBuffer, true);
    }

    @Override
    public void close() {
        if (released) {
            return;
        }
        released = true;
        bodyBuffer.release();
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
