package io.koraframework.http.server.netty.compression;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.*;
import io.netty.handler.codec.compression.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.function.*;

public final class Compressor {
    private final DefaultHttpCompressionStrategy strategy;
    private final int compressionLevel;
    private final Set<Algorithm> available;

    public Compressor(DefaultHttpCompressionStrategy strategy) {
        assert strategy.isEnabled();
        this.strategy = strategy;
        this.compressionLevel = strategy.getCompressionLevel();

        EnumSet<Algorithm> algorithms = EnumSet.of(Algorithm.GZIP, Algorithm.DEFLATE);
        this.available = Collections.unmodifiableSet(algorithms);
    }

    @Nullable
    public Session prepare(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response, long contentLength) {
        int code = response.status().code();
        if (code < 200 || code == 204 || code == 304 ||
            (request.method().equals(HttpMethod.HEAD) || (request.method().equals(HttpMethod.CONNECT) && code == 200)) ||
            response.protocolVersion() == HttpVersion.HTTP_1_0) {
            return null;
        }
        if (response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)) {
            return null;
        }
        if (!request.headers().contains(HttpHeaderNames.ACCEPT_ENCODING)) {
            return null;
        }
        Algorithm encoding = determineEncoding(request.headers().valueStringIterator(HttpHeaderNames.ACCEPT_ENCODING));
        if (encoding == null) {
            return null;
        }
        if (!strategy.shouldCompress(response, contentLength)) {
            return null;
        }

        response.headers().add(HttpHeaderNames.CONTENT_ENCODING, encoding.contentEncoding);

        Supplier<ChannelHandler> handler = switch (encoding) {
            case GZIP -> () -> ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP, compressionLevel);
            case DEFLATE -> () -> ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, compressionLevel);
        };

        OneShot oneShot = switch (encoding) {
            case GZIP -> (body, allocator) -> compressOneShot(ZlibWrapper.GZIP, compressionLevel, body, allocator);
            case DEFLATE -> (body, allocator) -> compressOneShot(ZlibWrapper.ZLIB, compressionLevel, body, allocator);
        };

        return new Session(ctx, handler, oneShot);
    }

    @Nullable
    private Algorithm determineEncoding(Iterator<String> acceptEncodingValues) {
        float starQ = -1.0f;
        float gzipQ = -1.0f;
        float deflateQ = -1.0f;
        while (acceptEncodingValues.hasNext()) {
            String header = acceptEncodingValues.next();
            int length = header.length();
            int start = 0;
            while (start <= length) {
                int end = header.indexOf(',', start);
                if (end == -1) {
                    end = length;
                }
                float q = 1.0f;
                int equalsPos = header.indexOf('=', start, end);
                if (equalsPos != -1) {
                    try {
                        q = Float.parseFloat(header.substring(equalsPos + 1, end));
                    } catch (NumberFormatException e) {
                        q = 0.0f;
                    }
                }
                if (header.indexOf("*", start, end) != -1) {
                    starQ = q;
                } else if (header.indexOf("gzip", start, end) != -1 && q > gzipQ) {
                    gzipQ = q;
                } else if (header.indexOf("deflate", start, end) != -1 && q > deflateQ) {
                    deflateQ = q;
                }
                start = end + 1;
            }
        }
        Algorithm best = null;
        float bestQ = 0.0f;
        for (Algorithm algorithm : Algorithm.BY_PREFERENCE) {
            float q = switch (algorithm) {
                case GZIP -> gzipQ;
                case DEFLATE -> deflateQ;
            };
            if (q == -1.0f) {
                q = starQ;
            }
            if (q > bestQ && available.contains(algorithm)) {
                best = algorithm;
                bestQ = q;
            }
        }
        return best;
    }

    private static @Nullable ByteBuf compressOneShot(ZlibWrapper wrapper, int level, ByteBuf body, ByteBufAllocator allocator) {
        EmbeddedChannel ch = new EmbeddedChannel(ZlibCodecFactory.newZlibEncoder(wrapper, level));
        try {
            ch.writeOutbound(body.retain());
            ch.finish();
            int size = ch.outboundMessages().size();
            if (size == 0) return null;
            if (size == 1) return (ByteBuf) ch.readOutbound();

            CompositeByteBuf composite = allocator.compositeBuffer(size);
            while (true) {
                ByteBuf part = ch.readOutbound();
                if (part == null) break;
                composite.addComponent(true, part);
            }
            return composite;
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    public enum Algorithm {
        GZIP(HttpHeaderValues.GZIP),
        DEFLATE(HttpHeaderValues.DEFLATE);

        static final Algorithm[] BY_PREFERENCE = values();
        final CharSequence contentEncoding;

        Algorithm(CharSequence contentEncoding) {
            this.contentEncoding = contentEncoding;
        }
    }

    @FunctionalInterface
    interface OneShot {
        @Nullable ByteBuf compress(ByteBuf body, ByteBufAllocator allocator);
    }

    public static final class Session {
        private final ChannelHandlerContext ctx;
        private final Supplier<ChannelHandler> handler;
        private final @Nullable OneShot oneShot;
        private @Nullable EmbeddedChannel compressionChannel;
        private @Nullable ByteBuf held;
        private @Nullable ByteBuf compressed;
        private boolean pushed = false;
        private boolean finished = false;

        Session(ChannelHandlerContext ctx, Supplier<ChannelHandler> handler, @Nullable OneShot oneShot) {
            this.ctx = ctx;
            this.handler = handler;
            this.oneShot = oneShot;
        }

        private EmbeddedChannel channel() {
            EmbeddedChannel channel = compressionChannel;
            if (channel == null) {
                channel = new EmbeddedChannel(
                    ctx.channel().id(),
                    ctx.channel().metadata().hasDisconnect(),
                    ctx.channel().config(),
                    handler.get()
                );
                compressionChannel = channel;
            }
            return channel;
        }

        public void push(ByteBuf data) {
            if (finished) {
                throw new IllegalStateException("Compression already finished");
            }
            if (!data.isReadable()) {
                data.release();
            } else if (oneShot != null && !pushed) {
                held = data;
                pushed = true;
            } else {
                pushed = true;
                EmbeddedChannel channel;
                boolean ready = false;
                try {
                    streamHeld();
                    channel = channel();
                    ready = true;
                } finally {
                    if (!ready) {
                        data.release();
                    }
                }
                channel.writeOutbound(data);
            }
        }

        private void streamHeld() {
            ByteBuf data = held;
            if (data != null) {
                EmbeddedChannel channel = channel();
                held = null;
                channel.writeOutbound(data);
            }
        }

        public void finish() {
            if (finished) {
                return;
            }
            ByteBuf body = held;
            if (body != null) {
                held = null;
                ByteBuf result = null;
                boolean compressedOnce = false;
                try {
                    result = Objects.requireNonNull(oneShot).compress(body, ctx.alloc());
                    compressedOnce = true;
                } finally {
                    if (!compressedOnce) {
                        body.release();
                        finished = true;
                    }
                }
                if (result != null) {
                    body.release();
                    compressed = result;
                    finished = true;
                    return;
                }
                EmbeddedChannel channel;
                boolean ready = false;
                try {
                    channel = channel();
                    ready = true;
                } finally {
                    if (!ready) {
                        body.release();
                        finished = true;
                    }
                }
                channel.writeOutbound(body);
            }
            channel().finish();
            finished = true;
        }

        public void discard() {
            ByteBuf body = held;
            if (body != null) {
                held = null;
                body.release();
            }
            ByteBuf result = compressed;
            if (result != null) {
                compressed = null;
                result.release();
            }
            if (!finished) {
                EmbeddedChannel channel = compressionChannel;
                if (channel != null) {
                    try {
                        channel.finishAndReleaseAll();
                    } catch (DecompressionException ignored) {
                    }
                }
                finished = true;
            }
        }

        public void fixContentLength(HttpResponse hr) {
            if (!finished) {
                throw new IllegalStateException("Compression not finished yet");
            }
            if (hr.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                long newContentLength = 0;
                ByteBuf result = compressed;
                if (result != null) {
                    newContentLength = result.readableBytes();
                } else {
                    for (Object outboundMessage : channel().outboundMessages()) {
                        newContentLength += ((ByteBuf) outboundMessage).readableBytes();
                    }
                }
                HttpUtil.setContentLength(hr, newContentLength);
            }
        }

        public @Nullable ByteBuf poll() {
            ByteBuf result = compressed;
            if (result != null) {
                compressed = null;
                return result;
            }
            streamHeld();
            EmbeddedChannel channel = compressionChannel;
            if (channel == null) {
                return null;
            }
            int n = channel.outboundMessages().size();
            if (n == 0) {
                return null;
            } else if (n == 1) {
                return channel.readOutbound();
            }
            CompositeByteBuf buf = channel.alloc().compositeBuffer(n);
            while (true) {
                ByteBuf item = channel.readOutbound();
                if (item == null) {
                    break;
                }
                buf.addComponent(true, item);
            }
            return buf;
        }
    }
}
