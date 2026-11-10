package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final FullHttpRequest nettyRequest;
    private final InputStream inputStream;

    public NettyRequestHttpBody(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
        this.inputStream = new ByteBufInputStream(nettyRequest.content().retain(), true);
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
        return inputStream;
    }

    @Override
    public void close() {
        try {
            inputStream.close();
        } catch (Exception ignored) {
        }
    }
}
