package io.koraframework.http.server.netty;

import io.koraframework.application.graph.*;
import io.koraframework.common.*;
import io.koraframework.common.readiness.*;
import io.koraframework.common.util.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.netty.NettySystemHttpServerModule.NettyResources;
import io.koraframework.http.server.netty.handler.*;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.*;
import io.koraframework.logging.common.arg.*;
import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.*;
import io.opentelemetry.context.Context;
import org.jspecify.annotations.*;
import org.slf4j.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.*;

public final class NettyHttpServer implements HttpServer, ReadinessProbe, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final AtomicReference<HttpServerState> state = new AtomicReference<>(HttpServerState.INIT);
    private final String name;
    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final HttpServerRouter httpServerRouter;
    private final ValueOf<KoraVirtualThreadDispatcher> virtualThreadDispatcher;
    private final ValueOf<NettyResponseWriteHandler> responseWriteHandler;
    private final @Nullable ValueOf<NettyTelemetryHandler> telemetryHandler;
    private final @Nullable Configurer<ServerBootstrap> configurer;
    private final boolean manageResources;
    private volatile NettyResources resources;
    private volatile @Nullable Channel serverChannel;

    public NettyHttpServer(
        String name,
        @Nullable NettyResources resources,
        ValueOf<HttpServerConfig> httpServerConfig,
        HttpServerRouter httpServerRouter,
        ValueOf<KoraVirtualThreadDispatcher> virtualThreadDispatcher,
        ValueOf<NettyResponseWriteHandler> responseWriteHandler,
        @Nullable ValueOf<NettyTelemetryHandler> telemetryHandler,
        @Nullable Configurer<ServerBootstrap> configurer
    ) {
        this.name = name;
        this.httpServerConfig = httpServerConfig;
        this.httpServerRouter = httpServerRouter;
        this.virtualThreadDispatcher = virtualThreadDispatcher;
        this.responseWriteHandler = responseWriteHandler;
        this.telemetryHandler = telemetryHandler;
        this.configurer = configurer;
        if (resources != null) {
            this.resources = resources;
            this.manageResources = false;
        } else {
            this.manageResources = true;
        }
    }

    @Override
    public void init() {
        try {
            log.debug("HTTP Server {} (Netty) starting...", name);
            final long started = TimeUtils.started();

            if (this.manageResources) {
                throw new IllegalStateException("HTTP Server 'manageResources=true' is not supported. Use external NettyResources from netty-common.");
            }

            var config = this.httpServerConfig.get();
            var bootstrap = new ServerBootstrap();
            bootstrap.group(resources.bossGroup(), resources.workerGroup())
                .channel(resources.channelClass())
                .option(ChannelOption.SO_BACKLOG, 1024);

            if (this.configurer != null) {
                bootstrap = this.configurer.configure(bootstrap.clone());
            }

            var waterMark = new WriteBufferWaterMark(32 * 1024, 64 * 1024);
            var telemetryValue = this.telemetryHandler;
            var currentRouter = this.httpServerRouter;
            var dispatcherValue = this.virtualThreadDispatcher;
            var writeHandlerValue = this.responseWriteHandler;

            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.socketKeepAliveEnabled())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        var config = httpServerConfig.get();
                        var telemetry = telemetryValue != null ? telemetryValue.get() : null;
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator((int) config.maxRequestBodySize().toBytes()));
                        var readTimeout = config.socketReadTimeout();
                        if (!readTimeout.isZero()) {
                            p.addLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        var writeTimeout = config.socketWriteTimeout();
                        if (!writeTimeout.isZero()) {
                            p.addLast(new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS));
                        }

                        p.addLast(new ChannelInboundHandlerAdapter() {
                            private HttpRequest currentRequest;

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (msg instanceof HttpRequest req) {
                                    this.currentRequest = req;
                                }
                                ctx.fireChannelRead(msg);
                            }

                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                if (evt instanceof io.netty.handler.timeout.ReadTimeoutException) {
                                    if (currentRequest != null) {
                                        var fullReq = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                                            currentRequest.protocolVersion(),
                                            currentRequest.method(),
                                            currentRequest.uri(),
                                            io.netty.buffer.Unpooled.EMPTY_BUFFER,
                                            currentRequest.headers(),
                                            io.netty.handler.codec.http.EmptyHttpHeaders.INSTANCE
                                        );
                                        ctx.fireChannelRead(fullReq);
                                        return;
                                    }
                                }
                                ctx.fireUserEventTriggered(evt);
                            }
                        });
                        if (telemetry != null) {
                            p.addLast(telemetry);
                        } else {
                            p.addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof FullHttpRequest nettyReq) {
                                        var koraReq = new io.koraframework.http.server.netty.request.NettyUnroutedHttpRequest(nettyReq);
                                        var invocation = currentRouter.route(koraReq);
                                        var container = new RequestContainer(
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
                            });
                        }
                        p.addLast(dispatcherValue.get());
                        p.addLast(writeHandlerValue.get());
                    }
                });

            int port = config.port();
            ChannelFuture bindFuture = bootstrap.bind(port).awaitUninterruptibly();
            if (!bindFuture.isSuccess()) {
                throw bindFuture.cause();
            }
            this.serverChannel = bindFuture.channel();
            this.state.set(HttpServerState.RUN);
            var data = StructuredArgument.marker("port", this.port());
            log.info(data, "HTTP Server {} (Netty) started in {}", name, TimeUtils.tookForLogging(started));
        } catch (Throwable e) {
            if (e instanceof BindException || e.getCause() instanceof BindException) {
                throw new IllegalStateException("HTTP server '%s' (Netty) failed to start on port '%s': port is already in use; stop the other process or configure a different port".formatted(name, httpServerConfig.get().port()), e);
            } else {
                throw new IllegalStateException("HTTP server '%s' (Netty) failed to start on port '%s': %s; check server config, handler initialization, and network binding".formatted(name, httpServerConfig.get().port(), e.getMessage()), e);
            }
        }
    }

    @Override
    public void release() {
        log.debug("HTTP Server {} (Netty) stopping...", name);
        this.state.set(HttpServerState.SHUTDOWN);
        final long started = TimeUtils.started();
        var dispatcher = this.virtualThreadDispatcher.get();
        dispatcher.setShuttingDown(true);
        try {
            log.debug("HTTP Server {} (Netty) awaiting graceful shutdown...", this.name);
            final Duration shutdownAwait = this.httpServerConfig.get().shutdownWait();
            dispatcher.getPhaser().awaitAdvanceInterruptibly(
                dispatcher.getPhaser().arrive(),
                shutdownAwait.toMillis(),
                TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException e) {
            log.warn("HTTP Server {} (Netty) failed completing graceful shutdown in {}", this.name, this.httpServerConfig.get().shutdownWait());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HTTP Server {} (Netty) graceful shutdown interrupted", this.name);
        }
        var localChannel = this.serverChannel;
        if (localChannel != null) {
            try {
                localChannel.close().await(this.httpServerConfig.get().shutdownWait().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dispatcher.getActiveRequests().get() > 0) {
            log.warn("HTTP Server {} (Netty) completed shutdown but {} requests are still active", this.name, dispatcher.getActiveRequests().get());
        } else {
            log.info("HTTP Server {} (Netty) stopped in {}", name, TimeUtils.tookForLogging(started));
        }
    }

    @Override
    public int port() {
        var localChannel = this.serverChannel;
        if (localChannel == null) {
            return -1;
        }
        var address = localChannel.localAddress();
        if (address instanceof InetSocketAddress inetAddress) {
            return inetAddress.getPort();
        }
        return -1;
    }

    @Nullable
    @Override
    public ReadinessProbeFailure probe() {
        return switch (this.state.get()) {
            case INIT -> new ReadinessProbeFailure("HTTP Server " + this.name + " (Netty) init");
            case RUN -> null;
            case SHUTDOWN -> new ReadinessProbeFailure("HTTP Server " + this.name + " (Netty) shutdown");
        };
    }

    private enum HttpServerState {
        INIT, RUN, SHUTDOWN
    }
}
