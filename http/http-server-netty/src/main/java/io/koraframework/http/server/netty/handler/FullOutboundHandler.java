package io.koraframework.http.server.netty.handler;

import io.netty.handler.codec.http.*;

import static io.netty.handler.codec.http.HttpUtil.*;

final class FullOutboundHandler extends OutboundHandler {
    private final FullHttpResponse message;
    private boolean written = false;

    FullOutboundHandler(KoraOutboundAccess outboundAccess, FullHttpResponse message) {
        super(outboundAccess);
        this.message = message;
    }

    @Override
    void markCloseAfterWrite() {
        if (!written) {
            setKeepAlive(message, false);
        }
    }

    @Override
    void writeSome() {
        written = true;
        try {
            writeCompressing(message, true, true);
        } catch (RuntimeException | Error e) {
            outboundAccess.parent().requestHandler.responseFailed(outboundAccess.attachment(), e);
            outboundAccess.parent().setOutboundHandler(null);
            super.discardOutbound();
            markResponseWritten();
            outboundAccess.parent().requiredFlushCoalescer().flushNow();
            outboundAccess.parent().requiredCtx().close();
            return;
        }
        outboundAccess.parent().setOutboundHandler(null);
        markResponseWritten();
        outboundAccess.parent().writeSome();
    }

    @Override
    void discardOutbound() {
        super.discardOutbound();
        outboundAccess.parent().setOutboundHandler(null);
        markResponseWritten();
        if (!written) {
            message.release();
        }
    }

    @Override
    void discardRefused() {
        super.discardRefused();
        message.release();
    }
}
