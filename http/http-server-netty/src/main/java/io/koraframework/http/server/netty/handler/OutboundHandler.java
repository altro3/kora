package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.compression.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.util.*;

public abstract class OutboundHandler {
    protected final KoraOutboundAccess outboundAccess;
    protected final PipeliningServerHandler parent;

    Compressor. @Nullable Session compressionSession;
    boolean responseWritten = false;

    OutboundHandler(KoraOutboundAccess outboundAccess, PipeliningServerHandler parent) {
        this.outboundAccess = Objects.requireNonNull(outboundAccess, "outboundAccess");
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    final void markResponseWritten() {
        if (!responseWritten) {
            responseWritten = true;
            parent.requestHandler.responseWritten(outboundAccess.attachment());
        }
    }

    private boolean shouldCloseAfterContent(boolean last) {
        return last && (outboundAccess.isCloseAfterWrite() || (parent.shuttingDown && parent.outboundQueue.isEmpty()));
    }

    protected final void writeCompressing(HttpContent content, boolean flush, boolean last) {
        if (this.compressionSession == null) {
            writePotentialEnd(content, flush, shouldCloseAfterContent(last));
        } else {
            writeCompressing0(content, flush, last);
        }
    }

    void writePotentialEnd(Object content, boolean flush, boolean close) {
        boolean last = content instanceof LastHttpContent;
        if (last) {
            outboundAccess.setFinalWriteIssued(true);
        }
        boolean keep = last && outboundAccess.isRecordFinalWrite();
        ChannelPromise recorded = null;
        if (keep) {
            recorded = parent.requiredCtx().newPromise();
            outboundAccess.setFinalWrite(recorded);
        }
        if (!last) {
            writeIntermediate(content, flush, close);
            return;
        }
        parent.write0(content, flush, close, false, recorded);
    }

    final void writeIntermediate(Object content, boolean flush, boolean close) {
        boolean watch = outboundAccess.isRecordFinalWrite();
        ChannelFuture future = parent.write0(content, flush, close, watch, null);
        if (watch) {
            future.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    parent.requestHandler.responseFailed(outboundAccess.attachment(), f.cause());
                }
            });
        }
    }

    private void writeCompressing0(HttpContent content, boolean flush, boolean last) {
        Compressor.Session session = Objects.requireNonNull(this.compressionSession);
        session.push(content.content());
        if (last) {
            session.finish();
        }
        if (content instanceof HttpResponse hr) {
            assert last;
            session.fixContentLength(hr);
            writeIntermediate(new DefaultHttpResponse(hr.protocolVersion(), hr.status(), hr.headers()), false, false);
        }
        boolean close = shouldCloseAfterContent(last);
        ByteBuf toSend = session.poll();
        if (toSend == null) {
            if (last) {
                HttpHeaders trailingHeaders = ((LastHttpContent) content).trailingHeaders();
                writePotentialEnd(trailingHeaders.isEmpty() ? LastHttpContent.EMPTY_LAST_CONTENT : new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, trailingHeaders), flush, close);
            } else if (flush || close) {
                writeIntermediate(new DefaultHttpContent(Unpooled.EMPTY_BUFFER), flush, close);
            }
        } else {
            if (last) {
                writePotentialEnd(new DefaultLastHttpContent(toSend, ((LastHttpContent) content).trailingHeaders()), flush, close);
            } else {
                writeIntermediate(new DefaultHttpContent(toSend), flush, close);
            }
        }
    }

    abstract void writeSome();

    abstract void markCloseAfterWrite();

    void discardOutbound() {
        if (compressionSession != null) {
            compressionSession.discard();
        }
    }

    void discardRefused() {
        if (compressionSession != null) {
            compressionSession.discard();
        }
    }
}
