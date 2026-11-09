package io.koraframework.http.server.netty;

import io.koraframework.application.graph.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.netty.handler.*;
import io.koraframework.http.server.netty.request.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.flush.*;
import io.netty.handler.stream.*;
import io.netty.handler.timeout.*;
import io.opentelemetry.context.*;

import java.util.concurrent.*;

public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final NettyHttpServer server;
    private final HttpServerConfig config;
    private final ValueOf<NettyTelemetryHandler> telemetryValue;
    private final HttpServerRouter currentRouter;
    private final ValueOf<KoraVirtualThreadDispatcher> dispatcherValue;
    private final ValueOf<NettyResponseWriteHandler> writeHandlerValue;

    public NettyServerChannelInitializer(NettyHttpServer server, HttpServerConfig config,
                                         ValueOf<NettyTelemetryHandler> telemetryValue, HttpServerRouter currentRouter,
                                         ValueOf<KoraVirtualThreadDispatcher> dispatcherValue, ValueOf<NettyResponseWriteHandler> writeHandlerValue) {
        this.server = server;
        this.config = config;
        this.telemetryValue = telemetryValue;
        this.currentRouter = currentRouter;
        this.dispatcherValue = dispatcherValue;
        this.writeHandlerValue = writeHandlerValue;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        var telemetry = telemetryValue != null ? telemetryValue.get() : null;

        var decoderConfig = new HttpDecoderConfig()
            .setMaxInitialLineLength(4096)
            .setMaxHeaderSize(8192);

        ChannelPipeline p = ch.pipeline();
        p.addLast("http-codec", new HttpServerCodec(decoderConfig));
        p.addLast("flushConsolidation", new FlushConsolidationHandler(256, true));
        p.addLast("chunked-writer", new ChunkedWriteHandler());

        var readTimeout = config.socketReadTimeout();
        if (!readTimeout.isZero()) {
            p.addLast("read-timeout", new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }
        var writeTimeout = config.socketWriteTimeout();
        if (!writeTimeout.isZero()) {
            p.addLast("write-timeout", new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }

        p.addLast(writeHandlerValue.get());
        p.addLast("request-streaming", new NettyRequestStreamingHandler(config));

        if (telemetry != null) {
            p.addLast("telemetry-handler", telemetry);
        } else {
            p.addLast("routing-fallback", new DefaultRoutingFallbackHandler(currentRouter));
        }
        p.addLast("virtual-thread-dispatcher", dispatcherValue.get());
    }
}
