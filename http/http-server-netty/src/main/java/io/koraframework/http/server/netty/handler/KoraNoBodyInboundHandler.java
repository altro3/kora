package io.koraframework.http.server.netty.handler;

import io.netty.handler.codec.http.*;
import io.netty.util.*;

public final class KoraNoBodyInboundHandler extends InboundHandler {
    private final PipeliningServerHandler parent;

    public KoraNoBodyInboundHandler(PipeliningServerHandler parent) {
        this.parent = parent;
    }

    @Override
    void read(Object message) {
        if (message instanceof LastHttpContent) {
            parent.setInboundHandler(parent.baseInboundHandler());
            return;
        }
        ReferenceCountUtil.release(message);
    }

    @Override
    void handleUpstreamError(Throwable cause) {
        parent.requestHandler.handleUnboundError(cause);
    }
}
