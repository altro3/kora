package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.*;
import io.netty.buffer.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.util.*;

public final class KoraOptimisticBufferingInboundHandler extends InboundHandler {
    private final PipeliningServerHandler parent;
    private final List<HttpContent> buffer = new ArrayList<>();

    @Nullable
    private HttpRequest request;
    @Nullable
    private KoraOutboundAccess outboundAccess;
    private long receivedLength;
    private boolean failed;

    public KoraOptimisticBufferingInboundHandler(PipeliningServerHandler parent) {
        this.parent = parent;
    }

    public void init(HttpRequest request, KoraOutboundAccess outboundAccess) {
        assert buffer.isEmpty();
        assert receivedLength == 0;
        assert !failed;
        this.request = request;
        this.outboundAccess = outboundAccess;
    }

    @Override
    void read(Object message) {
        HttpContent content = (HttpContent) message;
        boolean last = message.getClass() == DefaultLastHttpContent.class || message instanceof LastHttpContent;
        if (last && content.decoderResult().isFailure()) {
            parent.handleContentDecoderFailure(content);
            return;
        }
        if (content.content().isReadable()) {
            if (failed) {
                content.release();
                return;
            }
            int readableBytes = content.content().readableBytes();
            if (parent.bodySizeLimits.maxBodySize() != Long.MAX_VALUE && readableBytes > parent.bodySizeLimits.maxBodySize() - receivedLength) {
                long actualLength = receivedLength > Long.MAX_VALUE - readableBytes ? Long.MAX_VALUE : receivedLength + readableBytes;
                failed = true;
                content.release();
                handleUpstreamError(new IllegalStateException("Content length exceeded limit: " + parent.bodySizeLimits.maxBodySize() + ", actual: " + actualLength));
                if (last) {
                    parent.inboundHandler().read(LastHttpContent.EMPTY_LAST_CONTENT);
                }
                return;
            }
            receivedLength += readableBytes;
            buffer.add(content);
        } else {
            content.release();
        }

        if (last) {
            ByteBuf fullBody;
            if (buffer.isEmpty()) {
                fullBody = Unpooled.EMPTY_BUFFER;
            } else if (buffer.size() == 1) {
                fullBody = buffer.get(0).content().retain();
            } else {
                List<ByteBuf> pieces = new ArrayList<>(buffer.size());
                for (HttpContent c : buffer) {
                    pieces.add(c.content().retain());
                }
                buffer.clear();
                fullBody = parent.composeBody(parent.requiredCtx().alloc(), pieces);
            }
            buffer.clear();
            receivedLength = 0;
            HttpRequest req = this.request;
            this.request = null;
            KoraOutboundAccess outAccess = this.outboundAccess;
            this.outboundAccess = null;

            KoraAvailableBody body = new KoraAvailableBody(parent.bufferFactory().adapt(fullBody));
            parent.setInboundHandler(parent.baseInboundHandler());
            parent.accept(Objects.requireNonNull(req), body, Objects.requireNonNull(outAccess));
        }
    }

    @Override
    void readComplete() {
        devolveToStreaming();
        parent.inboundHandler().readComplete();
    }

    @Override
    void handleUpstreamError(Throwable cause) {
        devolveToStreaming();
        parent.inboundHandler().handleUpstreamError(cause);
    }

    private void devolveToStreaming() {
        if (this.request == null) return;
        HttpRequest req = Objects.requireNonNull(this.request);
        KoraOutboundAccess outAccess = Objects.requireNonNull(this.outboundAccess);
        this.request = null;
        this.outboundAccess = null;

        var streamingBody = new KoraStreamingNettyBody(parent.requiredCtx());
        var streamingInboundHandler = new KoraStreamingInboundHandler(streamingBody);

        if (parent.inboundHandler() == this) {
            parent.setInboundHandler(streamingInboundHandler);
        } else {
            ((KoraDecompressingInboundHandler) parent.inboundHandler()).setDelegate(streamingInboundHandler);
        }

        for (HttpContent content : buffer) {
            streamingInboundHandler.read(content);
        }
        buffer.clear();
        receivedLength = 0;
        failed = false;

        parent.accept(req, streamingBody, outAccess);
    }

    @Override
    void discard() {
        for (HttpContent content : buffer) {
            content.release();
        }
        buffer.clear();
        receivedLength = 0;
        failed = false;
    }
}
