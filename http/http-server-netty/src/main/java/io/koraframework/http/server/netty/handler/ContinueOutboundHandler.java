package io.koraframework.http.server.netty.handler;

import io.netty.handler.codec.http.*;

final class ContinueOutboundHandler extends OutboundHandler {
    static final FullHttpResponse CONTINUE_11 = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, io.netty.buffer.Unpooled.EMPTY_BUFFER);

    boolean written = false;
    @org.jspecify.annotations.Nullable OutboundHandler next;

    ContinueOutboundHandler(KoraOutboundAccess outboundAccess) {
        super(outboundAccess);
    }

    @Override
    void writeSome() {
        if (!written) {
            outboundAccess.parent().write0(CONTINUE_11, true, false, false, null);
            written = true;
        }
        if (next != null) {
            outboundAccess.parent().setOutboundHandler(next);
        }
    }

    @Override
    void markCloseAfterWrite() {
        if (next != null) {
            next.markCloseAfterWrite();
        }
    }

    @Override
    void discardOutbound() {
        super.discardOutbound();
        if (next != null) {
            next.discardOutbound();
            next = null;
        }
    }
}
