package io.koraframework.http.server.netty;

import io.koraframework.http.server.netty.compression.*;
import io.koraframework.http.server.netty.handler.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.*;
import io.netty.handler.ssl.*;
import io.netty.handler.timeout.*;
import io.netty.util.*;
import org.jspecify.annotations.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class KoraHttpPipelineBuilder {
    public static final AttributeKey<SslHandler> SSL_HANDLER_ATTRIBUTE = AttributeKey.valueOf("kora-ssl-handler");

    public static final String HANDLER_SSL = "ssl";
    public static final String HANDLER_LOGGING = "logging";
    public static final String HANDLER_IDLE_STATE = "idle-state";
    public static final String HANDLER_HTTP_CODEC = "http-codec";
    public static final String HANDLER_PIPELINING = "kora-pipelining";

    private final KoraNettyServerConfig config;
    private final RequestHandler requestHandler;
    @Nullable
    private final SslContext sslContext;
    @Nullable
    private final Compressor compressor;
    @Nullable
    private final LoggingHandler loggingHandler;

    public KoraHttpPipelineBuilder(
        KoraNettyServerConfig config,
        RequestHandler requestHandler,
        @Nullable SslContext sslContext) {
        this.config = Objects.requireNonNull(config, "config");
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");
        this.sslContext = sslContext;

        var compressionStrategy = new DefaultHttpCompressionStrategy(
            config.compressionThreshold(),
            config.compressionLevel(),
            config.maxZstdEncodeSize()
        );
        compressor = compressionStrategy.isEnabled() ? new Compressor(compressionStrategy) : null;
        loggingHandler = config.wireLogLevel() != null
            ? new LoggingHandler(KoraHttpPipelineBuilder.class, config.wireLogLevel())
            : null;
    }

    public Supplier<CompletionStage<?>> buildPipeline(Channel channel) {
        var pipeline = channel.pipeline();

        if (sslContext != null) {
            var sslHandler = sslContext.newHandler(channel.alloc());
            sslHandler.setHandshakeTimeoutMillis(config.sslHandshakeTimeout().toMillis());
            channel.attr(SSL_HANDLER_ATTRIBUTE).set(sslHandler);
            pipeline.addLast(HANDLER_SSL, sslHandler);
        }

        if (loggingHandler != null) {
            pipeline.addLast(HANDLER_LOGGING, loggingHandler);
        }

        long readIdleMillis = toMillisOrZero(config.socketReadTimeout());
        long writeIdleMillis = toMillisOrZero(config.socketWriteTimeout());
        long allIdleMillis = toMillisOrZero(config.idleTimeout());
        if (readIdleMillis > 0 || writeIdleMillis > 0 || allIdleMillis > 0) {
            pipeline.addLast(HANDLER_IDLE_STATE, new IdleStateHandler(
                readIdleMillis,
                writeIdleMillis,
                allIdleMillis,
                TimeUnit.MILLISECONDS
            ));
        }

        pipeline.addLast(HANDLER_HTTP_CODEC, createServerCodec());

        var serverHandler = new PipeliningServerHandler(requestHandler);
        serverHandler.setCompressor(compressor);
        serverHandler.setBodySizeLimits(new BodySizeLimits(config.maxRequestBodySize().toBytes(), config.maxRequestBufferSize()));
        serverHandler.setRequestDecompressionEnabled(config.requestDecompressionEnabled());
        serverHandler.setHttp10KeepAlive(config.http10KeepAlive());
        serverHandler.setRejectUnsupportedHttpVersions(config.rejectUnsupportedHttpVersions());

        pipeline.addLast(HANDLER_PIPELINING, serverHandler);
        return serverHandler::shutdownGracefully;
    }

    private HttpServerCodec createServerCodec() {
        var decoderConfig = new HttpDecoderConfig()
            .setMaxInitialLineLength(config.maxInitialLineLength())
            .setMaxHeaderSize(config.maxHeaderSize())
            .setMaxChunkSize(config.maxChunkSize())
            .setValidateHeaders(config.validateHeaders())
            .setInitialBufferSize(config.initialBufferSize())
            .setChunkedSupported(config.chunkedSupported());
        return new HttpServerCodec(decoderConfig);
    }

    private static long toMillisOrZero(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 0;
        }
        return duration.toMillis();
    }
}
