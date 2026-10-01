package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final FullHttpRequest nettyRequest;
    @Nullable
    private volatile InputStream inputStream;
    private boolean released = false;

    public NettyRequestHttpBody(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
    }

    public void prepare() {
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
        var in = this.inputStream;
        if (in != null) {
            return in;
        }

        var duplicate = nettyRequest.content().retainedDuplicate();
        var newIn = new ByteBufInputStream(duplicate, true);

        if (this.inputStream == null) {
            this.inputStream = newIn;
            return newIn;
        }

        duplicate.release();
        var winnerIn = this.inputStream;
        return winnerIn != null ? winnerIn : newIn;
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
        nettyRequest.release();
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
