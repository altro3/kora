package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.buffer.*;
import io.netty.handler.codec.http.*;

public final class KoraStreamingInboundHandler extends InboundHandler {
    private final KoraStreamingNettyBody targetBody;

    public KoraStreamingInboundHandler(KoraStreamingNettyBody targetBody) {
        this.targetBody = targetBody;
    }

    @Override
    boolean needMore() {
        return targetBody.isWritable();
    }

    @Override
    void read(Object message) {
        HttpContent content = (HttpContent) message;
        boolean last = message instanceof LastHttpContent;

        if (content.content().isReadable()) {
            targetBody.add(new KoraReadBuffer(content.content().retain()));
        }

        if (last) {
            targetBody.complete();
        }
        content.release();
    }

    @Override
    void handleUpstreamError(Throwable cause) {
        targetBody.error(cause);
    }

    @Override
    void discard() {
        targetBody.close();
    }
}
