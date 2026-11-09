package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.netty.request.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.opentelemetry.context.*;

public class DefaultRoutingFallbackHandler extends ChannelInboundHandlerAdapter {
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
