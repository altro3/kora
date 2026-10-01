package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final FullHttpRequest nettyRequest;

    public NettyRequestHttpBody(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
    }

    @Override
    public long contentLength() {
        String contentLengthStr = nettyRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        return contentLengthStr == null ? -1 : Long.parseLong(contentLengthStr);
    }

    @Nullable
    @Override
    public String contentType() {
        return nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    @NonNull
    public InputStream asInputStream() {
        return new ByteBufInputStream(nettyRequest.content().duplicate());
    }

    @Override
    public void close() throws IOException {
        this.asInputStream().close();
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
