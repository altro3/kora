package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.request.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import io.opentelemetry.api.trace.propagation.*;
import io.opentelemetry.context.*;

import static java.lang.IO.*;

@ChannelHandler.Sharable
public final class NettyTelemetryHandler extends ChannelInboundHandlerAdapter {

    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    private final HttpServerTelemetry telemetry;
    private final HttpServerRouter httpServerRouter;

    public NettyTelemetryHandler(HttpServerTelemetry telemetry, HttpServerRouter httpServerRouter) {
        this.telemetry = telemetry;
        this.httpServerRouter = httpServerRouter;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest nettyReq)) {
            ctx.fireChannelRead(msg);
            return;
        }

        HttpServerObservation observation = null;
        try {
            var rootCtx = PROPAGATOR.extract(Context.root(), nettyReq.headers(), NettyHttpHeaderMapExchange.INSTANCE);
            var koraReq = new NettyUnroutedHttpRequest(nettyReq);
            var invocation = httpServerRouter.route(koraReq);
            observation = telemetry.observe(invocation.routedRequest());

            var activeCtx = rootCtx.with(observation.span());
            var httpServerRequest = observation.observeRequest(invocation.routedRequest());

            var container = new RequestContainer(nettyReq, httpServerRequest, invocation, activeCtx, observation);
            ctx.fireChannelRead(container);
        } catch (Throwable t) {
            if (observation != null) {
                observation.observeError(t);
                observation.end();
            }
            ReferenceCountUtil.release(nettyReq);
            ctx.fireExceptionCaught(t);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        println("[DEBUG EXCEPTION] Exception in netty pipeline: " + cause.getMessage());
        ctx.fireExceptionCaught(cause);
    }

    public record RequestContainer(
        FullHttpRequest nettyReq,
        HttpServerRequest httpServerRequest,
        HttpServerInvocation invocation,
        Context telemetryContext,
        HttpServerObservation observation
    ) {}

    public record ResponseContainer(
        FullHttpRequest nettyReq,
        HttpServerResponse koraResponse,
        HttpServerObservation observation
    ) {}
}
