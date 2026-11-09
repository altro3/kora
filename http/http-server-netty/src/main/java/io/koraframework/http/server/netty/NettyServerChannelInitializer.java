package io.koraframework.http.server.netty;

import io.koraframework.application.graph.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.netty.handler.*;
import io.koraframework.http.server.netty.request.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.flush.FlushConsolidationHandler;
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
        p.addLast(new HttpServerCodec(decoderConfig));
        p.addLast(new ChunkedWriteHandler());

        var readTimeout = config.socketReadTimeout();
        if (!readTimeout.isZero()) {
            p.addLast("readTimeout", new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }
        var writeTimeout = config.socketWriteTimeout();
        if (!writeTimeout.isZero()) {
            p.addLast(new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }

        p.addLast(writeHandlerValue.get());
//            p.addLast(new RequestAggregationHandler(config));

        if (telemetry != null) {
            p.addLast(telemetry);
        } else {
            p.addLast(new DefaultRoutingFallbackHandler(currentRouter));
        }
        p.addLast(new FlushConsolidationHandler(256, true));
        p.addLast(dispatcherValue.get());
    }

    private static final class DefaultRoutingFallbackHandler extends ChannelInboundHandlerAdapter {
        private final HttpServerRouter currentRouter;

        public DefaultRoutingFallbackHandler(HttpServerRouter currentRouter) {
            this.currentRouter = currentRouter;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest nettyReq) {
                var koraReq = new NettyUnroutedHttpRequest(nettyReq);
                var invocation = currentRouter.route(koraReq);
                var container = new NettyTelemetryHandler.RequestContainer(
                    nettyReq,
                    invocation.routedRequest(),
                    invocation,
                    Context.root(),
                    null
                );
                ctx.fireChannelRead(container);
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }
}
