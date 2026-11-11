package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.buffer.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.Flow.*;

final class StreamingOutboundHandler extends OutboundHandler implements Subscriber<KoraReadBuffer> {
    private static final Logger LOG = LoggerFactory.getLogger(StreamingOutboundHandler.class);

    @Nullable
    private HttpResponse initialMessage;
    @Nullable
    private Subscription subscription;

    private boolean headWritten = false;
    private boolean completed = false;
    private boolean refused = false;

    StreamingOutboundHandler(KoraOutboundAccess outboundAccess, HttpResponse initialMessage, PipeliningServerHandler parent) {
        super(outboundAccess, parent);
        if (initialMessage instanceof FullHttpResponse) {
            throw new IllegalArgumentException("Cannot have a full response as the initial message of a streaming response");
        }
        this.initialMessage = Objects.requireNonNull(initialMessage, "initialMessage");
    }

    @Override
    void markCloseAfterWrite() {
        HttpResponse message = this.initialMessage;
        if (message != null) {
            HttpUtil.setKeepAlive(message, false);
        }
    }

    @Override
    void writeSome() {
        if (!headWritten && initialMessage != null) {
            headWritten = true;
            writeIntermediate(initialMessage, false, false);
            initialMessage = null;
        }

        Subscription sub = this.subscription;
        if (sub != null && !completed && !refused && parent.requiredCtx().channel().isWritable()) {
            sub.request(1);
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        if (parent.outboundHandler == this) {
            writeSome();
        }
    }

    @Override
    @SuppressWarnings("java:S1181")
    public void onNext(KoraReadBuffer item) {
        if (refused || completed) {
            item.close();
            return;
        }

        try {
            ByteBuf data = item.toByteBuf();
            writeCompressing(new DefaultHttpContent(data), true, false);

            if (parent.requiredCtx().channel().isWritable()) {
                Objects.requireNonNull(subscription).request(1);
            }
        } catch (RuntimeException | Error e) {
            fail(e);
        }
    }

    @Override
    @SuppressWarnings("java:S1181")
    public void onComplete() {
        if (completed || refused) return;
        completed = true;

        parent.setOutboundHandler(null);
        try {
            writeCompressing(LastHttpContent.EMPTY_LAST_CONTENT, true, true);
        } catch (RuntimeException | Error e) {
            fail(e);
            return;
        }

        if (!refused) {
            markResponseWritten();
        }
        parent.writeSome();
    }

    @Override
    public void onError(Throwable throwable) {
        fail(throwable);
    }

    private void fail(Throwable t) {
        if (completed || refused) return;
        completed = true;

        parent.requestHandler.responseFailed(outboundAccess.attachment(), t);
        if (LOG.isWarnEnabled()) {
            if (!headWritten) {
                LOG.warn("The streamed response body failed before the response was written. The error cannot be forwarded to the client.", t);
            } else {
                LOG.warn("The streamed response body failed after some of it was written. The error cannot be forwarded to the client, and the response ends abruptly.", t);
            }
        }

        parent.setOutboundHandler(null);
        discardOutbound();
        parent.requiredCtx().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    void discardOutbound() {
        super.discardOutbound();
        completed = true;
        Subscription sub = this.subscription;
        if (sub != null) {
            this.subscription = null;
            sub.cancel();
        }
        parent.setOutboundHandler(null);
    }

    @Override
    void discardRefused() {
        super.discardRefused();
        refused = true;
        completed = true;
        Subscription sub = this.subscription;
        if (sub != null) {
            this.subscription = null;
            sub.cancel();
        }
    }
}
