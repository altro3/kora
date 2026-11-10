package io.koraframework.http.server.netty;

import io.koraframework.application.graph.*;
import io.koraframework.common.*;
import io.koraframework.common.readiness.*;
import io.koraframework.common.util.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.netty.NettySystemHttpServerModule.*;
import io.koraframework.http.server.netty.handler.*;
import io.koraframework.logging.common.arg.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class NettyHttpServer implements HttpServer, ReadinessProbe, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final AtomicReference<HttpServerState> state = new AtomicReference<>(HttpServerState.INIT);
    private final String name;
    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final ValueOf<KoraVirtualThreadHandler> koraVirtualThreadHandler;
    private final @Nullable Configurer<ServerBootstrap> configurer;
    private final boolean manageResources;
    private volatile NettyResources resources;
    private volatile @Nullable Channel serverChannel;

    public NettyHttpServer(
        String name,
        @Nullable NettyResources resources,
        ValueOf<HttpServerConfig> httpServerConfig,
        ValueOf<KoraVirtualThreadHandler> koraVirtualThreadHandler,
        @Nullable Configurer<ServerBootstrap> configurer
    ) {
        this.name = name;
        this.httpServerConfig = httpServerConfig;
        this.koraVirtualThreadHandler = koraVirtualThreadHandler;
        this.configurer = configurer;
        if (resources != null) {
            this.resources = resources;
            manageResources = false;
        } else {
            manageResources = true;
        }
    }

    @Override
    public void init() {
        try {
            log.debug("HTTP Server {} (Netty) starting...", name);
            final long started = TimeUtils.started();

            if (manageResources) {
                throw new IllegalStateException("HTTP Server 'manageResources=true' is not supported. Use external NettyResources from netty-common.");
            }

            var config = httpServerConfig.get();
            var bootstrap = new ServerBootstrap();
            var recvAllocator = new FixedRecvByteBufAllocator(4096);

            bootstrap.group(resources.bossGroup(), resources.workerGroup())
                .channel(resources.channelClass())
                .option(ChannelOption.SO_BACKLOG, 4096);

            if (configurer != null) {
                bootstrap = configurer.configure(bootstrap.clone());
            }

            var waterMark = new WriteBufferWaterMark(1024 * 1024, 2 * 1024 * 1024);

            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.socketKeepAliveEnabled())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(false, true))
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                .childOption(ChannelOption.RECVBUF_ALLOCATOR, recvAllocator)
                .childHandler(new NettyServerChannelInitializer(this, config, koraVirtualThreadHandler));

            int port = config.port();
            ChannelFuture bindFuture = bootstrap.bind(port).awaitUninterruptibly();
            if (!bindFuture.isSuccess()) {
                throw bindFuture.cause();
            }
            serverChannel = bindFuture.channel();
            state.set(HttpServerState.RUN);
            var data = StructuredArgument.marker("port", port());
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
        state.set(HttpServerState.SHUTDOWN);
        final long started = TimeUtils.started();

        var dispatcher = koraVirtualThreadHandler.get();

        try {
            log.debug("HTTP Server {} (Netty) awaiting graceful shutdown...", name);
            dispatcher.shutdown();
        } catch (Throwable e) {
            log.warn("HTTP Server {} (Netty) graceful shutdown interrupted or failed", name, e);
        }
        var localChannel = serverChannel;
        if (localChannel != null) {
            try {
                localChannel.close().await(httpServerConfig.get().shutdownWait().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("HTTP Server {} (Netty) stopped in {}", name, TimeUtils.tookForLogging(started));
    }

    @Override
    public int port() {
        var localChannel = serverChannel;
        if (localChannel == null) {
            return -1;
        }
        var address = localChannel.localAddress();
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getPort();
        }
        return -1;
    }

    @Nullable
    @Override
    public ReadinessProbeFailure probe() {
        HttpServerState currentState = state.get();
        if (currentState == HttpServerState.INIT) {
            return new ReadinessProbeFailure("HTTP Server " + name + " (Netty) init");
        }
        if (currentState == HttpServerState.RUN) {
            return null;
        }
        if (currentState == HttpServerState.SHUTDOWN) {
            return new ReadinessProbeFailure("HTTP Server " + name + " (Netty) shutdown");
        }
        return new ReadinessProbeFailure("HTTP Server " + name + " (Netty) unknown state");
    }

    private enum HttpServerState {
        INIT, RUN, SHUTDOWN
    }
}
