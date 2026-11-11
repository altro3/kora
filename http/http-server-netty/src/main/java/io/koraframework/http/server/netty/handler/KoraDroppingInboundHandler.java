package io.koraframework.http.server.netty.handler;

import io.netty.handler.codec.http.*;
import io.netty.util.*;

public final class KoraDroppingInboundHandler extends InboundHandler {
    private final PipeliningServerHandler parent;

    public KoraDroppingInboundHandler(PipeliningServerHandler parent) {
        this.parent = parent;
    }

    @Override
    void read(Object message) {
        ReferenceCountUtil.release(message);
        if (message instanceof LastHttpContent) {
            parent.setInboundHandler(parent.baseInboundHandler());
        }
    }

    @Override
    void handleUpstreamError(Throwable cause) {
    }
}
