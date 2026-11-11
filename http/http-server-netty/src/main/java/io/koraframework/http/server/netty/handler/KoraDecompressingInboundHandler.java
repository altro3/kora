package io.koraframework.http.server.netty.handler;

import io.netty.channel.embedded.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;

public final class KoraDecompressingInboundHandler extends InboundHandler {
    private final EmbeddedChannel decompressionChannel;
    private InboundHandler delegate;

    public KoraDecompressingInboundHandler(EmbeddedChannel decompressionChannel, InboundHandler delegate) {
        this.decompressionChannel = decompressionChannel;
        this.delegate = delegate;
    }

    public void setDelegate(InboundHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    boolean needMore() {
        return delegate.needMore();
    }

    @Override
    void read(Object message) {
        HttpContent content = (HttpContent) message;
        decompressionChannel.writeInbound(content.content().retain());

        while (true) {
            Object decompressed = decompressionChannel.readInbound();
            if (decompressed == null) {
                break;
            }
            delegate.read(decompressed);
        }

        if (message instanceof LastHttpContent) {
            decompressionChannel.finish();
            while (true) {
                Object decompressed = decompressionChannel.readInbound();
                if (decompressed == null) {
                    break;
                }
                delegate.read(decompressed);
            }
            decompressionChannel.finishAndReleaseAll();
            delegate.read(LastHttpContent.EMPTY_LAST_CONTENT);
        }
        ReferenceCountUtil.release(message);
    }

    @Override
    void handleUpstreamError(Throwable cause) {
        delegate.handleUpstreamError(cause);
    }

    @Override
    void readComplete() {
        delegate.readComplete();
    }

    @Override
    void discard() {
        try {
            decompressionChannel.finishAndReleaseAll();
        } catch (Throwable ignored) {
        }
        delegate.discard();
    }
}
