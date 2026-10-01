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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private static final AtomicReferenceFieldUpdater<NettyRequestHttpBody, InputStream> INPUT_STREAM_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(NettyRequestHttpBody.class, InputStream.class, "inputStream");

    private final FullHttpRequest nettyRequest;
    private volatile InputStream inputStream;

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
        in = new ByteBufInputStream(duplicate, true);

        if (INPUT_STREAM_UPDATER.compareAndSet(this, null, in)) {
            return in;
        }

        try {
            in.close();
        } catch (IOException ignored) {}

        return this.inputStream;
    }

    @Override
    public void close() throws IOException {
        var in = this.inputStream;
        if (in != null) {
            in.close();
        } else {
            var duplicate = nettyRequest.content().retainedDuplicate();
            new ByteBufInputStream(duplicate, true).close();
        }
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
