package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.body.*;
import io.netty.channel.*;
import io.netty.channel.embedded.*;
import io.netty.handler.codec.compression.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import org.jspecify.annotations.*;

public final class KoraMessageInboundHandler extends InboundHandler {
    private final PipeliningServerHandler parent;

    public KoraMessageInboundHandler(PipeliningServerHandler parent) {
        this.parent = parent;
    }

    @Override
    void read(Object message) {
        if (!(message instanceof HttpRequest request)) {
            if (message instanceof LastHttpContent last && last.decoderResult().isFailure()) {
                parent.closeAfterContentDecoderFailure();
            }
            ReferenceCountUtil.release(message);
            return;
        }

        var outboundAccess = new KoraOutboundAccess(request, parent.requiredCtx(), parent);
        parent.outboundQueue.add(outboundAccess);

        EmbeddedChannel decompressionChannel = null;
        if (parent.requestDecompressionEnabled) {
            var headers = request.headers();
            var contentEncoding = getContentEncoding(headers);
            if (contentEncoding != null) {
                if (HttpHeaderValues.GZIP.contentEqualsIgnoreCase(contentEncoding) || HttpHeaderValues.X_GZIP.contentEqualsIgnoreCase(contentEncoding)) {
                    decompressionChannel = newDecompressionChannel(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP, maxZlibAllocation()));
                } else if (HttpHeaderValues.DEFLATE.contentEqualsIgnoreCase(contentEncoding) || HttpHeaderValues.X_DEFLATE.contentEqualsIgnoreCase(contentEncoding)) {
                    decompressionChannel = newDecompressionChannel(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.ZLIB_OR_NONE, maxZlibAllocation()));
                }
                if (decompressionChannel != null) {
                    headers.remove(HttpHeaderNames.CONTENT_LENGTH);
                    headers.remove(HttpHeaderNames.CONTENT_ENCODING);
                    headers.add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }
            }
        }

        boolean full = request.getClass() != DefaultHttpRequest.class && request instanceof FullHttpRequest;
        if (full && decompressionChannel == null) {
            var fullRequest = (FullHttpRequest) request;
            var body = new KoraAvailableBody(parent.bufferFactory().adapt(fullRequest.content().retain()));
            parent.accept(request, body, outboundAccess);
        } else if (!parent.hasBody(request)) {
            parent.setInboundHandler(request.decoderResult().isFailure() ? parent.droppingInboundHandler : parent.noBodyInboundHandler);
            if (full) {
                parent.inboundHandler().read(message);
            }
            if (decompressionChannel != null) {
                decompressionChannel.finish();
            }
            parent.accept(request, null, outboundAccess);
        } else {
            parent.optimisticBufferingInboundHandler().init(request, outboundAccess);
            if (decompressionChannel == null) {
                parent.setInboundHandler(parent.optimisticBufferingInboundHandler());
            } else {
                parent.setInboundHandler(new KoraDecompressingInboundHandler(parent, decompressionChannel, parent.optimisticBufferingInboundHandler()));
            }
            if (full) {
                var fullRequest = (FullHttpRequest) request;
                parent.inboundHandler().read(new DefaultLastHttpContent(fullRequest.content().retain(), fullRequest.trailingHeaders()));
            }
        }
    }

    private EmbeddedChannel newDecompressionChannel(ChannelHandler decompressor) {
        var channel = new EmbeddedChannel(
            parent.requiredCtx().channel().id(),
            parent.requiredCtx().channel().metadata().hasDisconnect(),
            parent.requiredCtx().channel().config()
        );
        channel.pipeline().addLast("decompressor", decompressor);
        return channel;
    }

    private int maxZlibAllocation() {
        long maxBufferSize = parent.bodySizeLimits.maxBufferSize();
        if (maxBufferSize == Long.MAX_VALUE || maxBufferSize > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) Math.max(1, maxBufferSize);
    }

    @Nullable
    private static String getContentEncoding(HttpHeaders headers) {
        var contentEncoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
        if (contentEncoding != null) {
            return contentEncoding.trim();
        }
        var transferEncoding = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        if (transferEncoding != null) {
            int idx = transferEncoding.indexOf(',');
            if (idx != -1) {
                return transferEncoding.substring(0, idx).trim();
            }
            return transferEncoding.trim();
        }
        return null;
    }

    @Override
    void handleUpstreamError(Throwable cause) {
        parent.requestHandler.handleUnboundError(cause);
    }
}
