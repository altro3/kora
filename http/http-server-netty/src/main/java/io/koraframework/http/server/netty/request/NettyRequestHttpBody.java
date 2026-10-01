package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final FullHttpRequest nettyRequest;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public NettyRequestHttpBody(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
        nettyRequest.retain();
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
        return new ByteBufInputStream(nettyRequest.content().duplicate(), false);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            nettyRequest.release();
        }
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
