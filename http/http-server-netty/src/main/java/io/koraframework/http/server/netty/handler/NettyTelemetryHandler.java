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
import org.slf4j.*;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

@ChannelHandler.Sharable
public final class NettyTelemetryHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(NettyTelemetryHandler.class);

    public static final AttributeKey<HttpServerObservation> OBSERVATION_KEY = AttributeKey.valueOf("kora.http.observation");
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
        ctx.channel().attr(OBSERVATION_KEY).set(observation);

        var activeCtx = rootCtx.with(observation.span());
        var httpServerRequest = observation.observeRequest(invocation.routedRequest());

        var container = new RequestContainer(nettyReq, httpServerRequest, invocation, activeCtx, observation);
        ctx.fireChannelRead(container);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        var observation = ctx.channel().attr(OBSERVATION_KEY).get();

        if (observation != null && msg instanceof LastHttpContent) {
            promise.addListener(future -> {
                var obs = ctx.channel().attr(OBSERVATION_KEY).getAndSet(null);
                if (obs != null) {
                    if (!future.isSuccess()) {
                        var cause = future.cause();
                        if (!(cause instanceof ClosedChannelException) && ctx.channel().isActive()) {
                            obs.observeError(cause);
                        }
                    }
                    obs.end();
                }
            });
        }

        ctx.write(msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        var observation = ctx.channel().attr(OBSERVATION_KEY).get();
        if (observation != null) {
            observation.observeError(new IOException("Connection closed by peer prematurely"));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.info("[DEBUG] NettyTelemetryHandler.exceptionCaught! Cause: {}", cause.getClass().getName());
        var observation = ctx.channel().attr(OBSERVATION_KEY).get();
        log.info("[DEBUG] Observation in exceptionCaught: {}", observation != null);
        if (observation != null) {
            try (var scope = observation.span().makeCurrent()) {
                observation.observeError(cause);
            } finally {
                observation.end();
            }
        }
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
