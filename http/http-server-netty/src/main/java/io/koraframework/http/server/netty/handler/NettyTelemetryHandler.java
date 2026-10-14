package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.request.HttpServerRequest;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.common.router.HttpServerInvocation;
import io.koraframework.http.server.common.router.HttpServerRouter;
import io.koraframework.http.server.common.telemetry.HttpServerObservation;
import io.koraframework.http.server.common.telemetry.HttpServerTelemetry;
import io.koraframework.http.server.netty.request.NettyUnroutedHttpRequest;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

@ChannelHandler.Sharable
public final class NettyTelemetryHandler extends ChannelDuplexHandler {

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

        var rootCtx = PROPAGATOR.extract(Context.root(), nettyReq.headers(), NettyHttpHeaderMapExchange.INSTANCE);
        var koraReq = new NettyUnroutedHttpRequest(nettyReq);
        var invocation = this.httpServerRouter.route(koraReq);

        var observation = this.telemetry.observe(invocation.routedRequest());
        var activeCtx = rootCtx.with(observation.span());
        var httpServerRequest = observation.observeRequest(invocation.routedRequest());

        var container = new RequestContainer(nettyReq, httpServerRequest, invocation, activeCtx, observation);
        ctx.fireChannelRead(container);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof ResponseContainer responseContainer)) {
            ctx.write(msg, promise);
            return;
        }

        var observation = responseContainer.observation();
        if (observation != null) {
            var trackedResponse = observation.observeResponse(responseContainer.koraResponse());
            var updatedContainer = new ResponseContainer(
                responseContainer.nettyReq(),
                trackedResponse,
                null
            );

            promise.addListener(future -> {
                try {
                    if (future.isSuccess()) {
                        observation.end();
                    } else {
                        observation.observeError(future.cause());
                        observation.end();
                    }
                } catch (Throwable t) {
                    observation.end();
                }
            });

            ctx.write(updatedContainer, promise);
        } else {
            ctx.write(responseContainer, promise);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
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
