package io.koraframework.http.server.netty;

import io.koraframework.application.graph.Lifecycle;
import io.koraframework.application.graph.ValueOf;
import io.koraframework.common.Configurer;
import io.koraframework.common.readiness.ReadinessProbe;
import io.koraframework.common.readiness.ReadinessProbeFailure;
import io.koraframework.common.util.TimeUtils;
import io.koraframework.http.server.common.HttpServer;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.netty.NettyResourceLifecycle.NettyResources;
import io.koraframework.http.server.netty.handler.KoraHttpServerHandler;
import io.koraframework.http.server.netty.handler.NettyHttpHandler;
import io.koraframework.logging.common.arg.StructuredArgument;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class NettyHttpServer implements HttpServer, ReadinessProbe, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final AtomicReference<HttpServerState> state = new AtomicReference<>(HttpServerState.INIT);
    private final String name;
    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final ValueOf<NettyHttpHandler> httpHandler;
    @Nullable
    private final Configurer<ServerBootstrap> configurer;
    private final boolean manageResources;
    private volatile NettyResources resources;

    private volatile @Nullable Channel serverChannel;

    public NettyHttpServer(
        String name,
        @Nullable NettyResources resources,
        ValueOf<HttpServerConfig> httpServerConfig,
        ValueOf<NettyHttpHandler> httpHandler,
        @Nullable Configurer<ServerBootstrap> configurer
    ) {
        this.name = name;
        this.httpServerConfig = httpServerConfig;
        this.httpHandler = httpHandler;
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
                var boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
                var worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
                this.resources = new NettyResources(boss, worker, NioServerSocketChannel.class);
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
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.socketKeepAliveEnabled())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        var config = httpServerConfig.get();
                        var readTimeout = config.socketReadTimeout();
                        if (!readTimeout.isZero()) {
                            p.addLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        var writeTimeout = config.socketWriteTimeout();
                        if (!writeTimeout.isZero()) {
                            p.addLast(new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS));
                        }
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator((int) config.maxRequestBodySize().toBytes()));
                        p.addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                httpHandler.get().handle(ctx, msg);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                try {
                                    if (cause instanceof ReadTimeoutException
                                        || cause instanceof WriteTimeoutException) {
                                        ctx.close();
                                        return;
                                    }
                                    httpHandler.get().exceptionCaught(ctx, cause);
                                } catch (Throwable t) {
                                    ctx.close();
                                }
                            }
                        });
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

        if (this.manageResources) {
            long timeoutMs = this.httpServerConfig.get().shutdownWait().toMillis();
            long quietPeriodMs = Math.min(2000, timeoutMs);
            var bossFuture = this.resources.bossGroup().shutdownGracefully(quietPeriodMs, timeoutMs, TimeUnit.MILLISECONDS);
            var workerFuture = this.resources.workerGroup().shutdownGracefully(quietPeriodMs, timeoutMs, TimeUnit.MILLISECONDS);
            bossFuture.awaitUninterruptibly(timeoutMs, TimeUnit.MILLISECONDS);
            workerFuture.awaitUninterruptibly(timeoutMs, TimeUnit.MILLISECONDS);
        }

        var localChannel = this.serverChannel;
        ChannelFuture closeFuture = null;
        if (localChannel != null) {
            closeFuture = localChannel.close();
        }

        final Duration shutdownAwait = this.httpServerConfig.get().shutdownWait();

        NettyHttpHandler actualHandler = this.httpHandler.get();
        if (actualHandler instanceof KoraHttpServerHandler koraHandler) {
            koraHandler.setShuttingDown(true);

            try {
                log.debug("HTTP Server {} (Netty) awaiting graceful shutdown...", this.name);
                koraHandler.getPhaser().awaitAdvanceInterruptibly(
                    koraHandler.getPhaser().arrive(),
                    shutdownAwait.toMillis(),
                    TimeUnit.MILLISECONDS
                );
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

            if (koraHandler.getActiveRequests().get() > 0) {
                log.warn("HTTP Server {} (Netty) completed shutdown but {} requests are still active", this.name, koraHandler.getActiveRequests().get());
            } else {
                log.info("HTTP Server {} (Netty) stopped in {}", name, TimeUtils.tookForLogging(started));
            }
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
