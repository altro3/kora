package io.koraframework.http.server.netty;

import io.koraframework.application.graph.Lifecycle;
import io.koraframework.application.graph.ValueOf;
import io.koraframework.common.Configurer;
import io.koraframework.common.readiness.ReadinessProbe;
import io.koraframework.common.readiness.ReadinessProbeFailure;
import io.koraframework.common.util.TimeUtils;
import io.koraframework.http.server.common.HttpServer;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.router.HttpServerRouter;
import io.koraframework.http.server.common.telemetry.HttpServerTelemetry;
import io.koraframework.http.server.netty.handler.KoraHttpServerHandler;
import io.koraframework.logging.common.arg.StructuredArgument;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class NettyHttpServer implements HttpServer, ReadinessProbe, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final Phaser phaser = new Phaser(1);
    private final AtomicReference<HttpServerState> state = new AtomicReference<>(HttpServerState.INIT);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final String name;
    private final NettyResourceLifecycle.NettyResources resources;
    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final HttpServerRouter httpServerRouter;
    private final HttpServerTelemetry telemetry;
    @Nullable
    private final Configurer<ServerBootstrap> configurer;

    private volatile @Nullable Channel serverChannel;
    private volatile boolean shuttingDown = false;

    public NettyHttpServer(
        String name,
        NettyResourceLifecycle.NettyResources resources,
        ValueOf<HttpServerConfig> httpServerConfig,
        HttpServerRouter httpServerRouter,
        HttpServerTelemetry telemetry,
        @Nullable Configurer<ServerBootstrap> configurer
    ) {
        this.name = name;
        this.resources = resources;
        this.httpServerConfig = httpServerConfig;
        this.httpServerRouter = httpServerRouter;
        this.telemetry = telemetry;
        this.configurer = configurer;
    }

    @Override
    public void init() {
        try {
            log.debug("HTTP Server {} (Netty) starting...", name);
            final long started = TimeUtils.started();

            var config = this.httpServerConfig.get();
            var bootstrap = new ServerBootstrap();
            bootstrap.group(resources.bossGroup(), resources.workerGroup())
                .channel(resources.channelClass())
                .option(ChannelOption.SO_BACKLOG, 1024);

            if (this.configurer != null) {
                bootstrap = this.configurer.configure(bootstrap.clone());
            }

            var waterMark = new WriteBufferWaterMark(32 * 1024, 64 * 1024);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.socketKeepAliveEnabled())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator((int) config.maxRequestBodySize().toBytes()));
                        p.addLast(new KoraHttpServerHandler(
                            config,
                            httpServerRouter,
                            telemetry,
                            activeRequests,
                            phaser,
                            () -> shuttingDown
                        ));
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
        this.shuttingDown = true;

        var localChannel = this.serverChannel;
        ChannelFuture closeFuture = null;
        if (localChannel != null) {
            closeFuture = localChannel.close();
        }

        final Duration shutdownAwait = this.httpServerConfig.get().shutdownWait();
        try {
            log.debug("HTTP Server {} (Netty) awaiting graceful shutdown...", this.name);
            phaser.awaitAdvanceInterruptibly(phaser.arrive(), shutdownAwait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("HTTP Server {} (Netty) failed completing graceful shutdown in {}", this.name, shutdownAwait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HTTP Server {} (Netty) graceful shutdown interrupted", this.name);
        }

        if (closeFuture != null) {
            try {
                closeFuture.await(shutdownAwait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (activeRequests.get() > 0) {
            log.warn("HTTP Server {} (Netty) completed shutdown but {} requests are still active", this.name, activeRequests.get());
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
        var address = (InetSocketAddress) localChannel.localAddress();
        return address == null ? -1 : address.getPort();
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
