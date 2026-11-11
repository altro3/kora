package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.buffer.KoraReadBuffer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;

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

        KoraOutboundAccess outboundAccess = new KoraOutboundAccess(request, parent.requiredCtx(), parent);
        parent.outboundQueue.add(outboundAccess);

        EmbeddedChannel decompressionChannel = null;
        if (parent.requestDecompressionEnabled) {
            HttpHeaders headers = request.headers();
            String contentEncoding = getContentEncoding(headers);
            if (contentEncoding != null) {
                if (HttpHeaderValues.GZIP.contentEqualsIgnoreCase(contentEncoding) || HttpHeaderValues.X_GZIP.contentEqualsIgnoreCase(contentEncoding)) {
                    decompressionChannel = newDecompressionChannel(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP, maxZlibAllocation()));
                } else if (HttpHeaderValues.DEFLATE.contentEqualsIgnoreCase(contentEncoding) || HttpHeaderValues.X_DEFLATE.contentEqualsIgnoreCase(contentEncoding)) {
                    decompressionChannel = newDecompressionChannel(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB_OR_NONE, maxZlibAllocation()));
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
            FullHttpRequest fullRequest = (FullHttpRequest) request;
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
                parent.setInboundHandler(new KoraDecompressingInboundHandler(decompressionChannel, parent.optimisticBufferingInboundHandler()));
            }
            if (full) {
                FullHttpRequest fullRequest = (FullHttpRequest) request;
                parent.inboundHandler().read(new DefaultLastHttpContent(fullRequest.content().retain(), fullRequest.trailingHeaders()));
            }
        }
    }

    private EmbeddedChannel newDecompressionChannel(ChannelHandler decompressor) {
        EmbeddedChannel channel = new EmbeddedChannel(parent.requiredCtx().channel().id(),
                parent.requiredCtx().channel().metadata().hasDisconnect(), parent.requiredCtx().channel().config());
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
        String contentEncoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
        if (contentEncoding != null) {
            return contentEncoding.trim();
        }
        String transferEncoding = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        if (transferEncoding != null) {
            int idx = transferEncoding.indexOf(",");
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
