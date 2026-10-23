package io.koraframework.http.server.undertow;

import io.koraframework.application.graph.*;
import io.koraframework.common.*;
import io.koraframework.common.readiness.*;
import io.koraframework.common.util.*;
import io.koraframework.http.server.common.*;
import io.koraframework.logging.common.arg.*;
import io.undertow.*;
import io.undertow.server.*;
import io.undertow.server.handlers.*;
import org.jspecify.annotations.*;
import org.slf4j.*;
import org.xnio.*;

import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.*;

public class UndertowHttpServer implements HttpServer, ReadinessProbe {

    private static final Logger logger = LoggerFactory.getLogger(UndertowHttpServer.class);

    private final AtomicReference<HttpServerState> state = new AtomicReference<>(HttpServerState.INIT);
    private final ValueOf<HttpHandler> httpHandler;
    private final ValueOf<HttpServerConfig> httpServerConfig;
    private final ValueOf<UndertowConfig> undertowConfig;
    private final GracefulShutdownHandler gracefulShutdown;
    private final String name;
    private final XnioWorker xnioWorker;
    @Nullable
    private final Configurer<Undertow.Builder> configurer;

    private volatile Undertow undertow;

    public UndertowHttpServer(String name,
                              ValueOf<UndertowConfig> undertowConfig,
                              ValueOf<HttpHandler> httpHandler,
                              XnioWorker xnioWorker,
                              ValueOf<HttpServerConfig> httpServerConfig,
                              @Nullable Configurer<Undertow.Builder> configurer) {
        this.httpHandler = httpHandler;
        this.httpServerConfig = httpServerConfig;
        this.undertowConfig = undertowConfig;
        this.name = name;
        this.xnioWorker = xnioWorker;
        this.configurer = configurer;

        this.gracefulShutdown = new GracefulShutdownHandler(this::handleRequest);
    }

    private void handleRequest(HttpServerExchange exchange) throws Exception {
        this.httpHandler.get().handleRequest(exchange);
    }

    @Override
    public void init() {
        try {
            logger.debug("HTTP Server {} (Undertow) starting...", name);
            final long started = TimeUtils.started();
            this.gracefulShutdown.start();
            this.undertow = this.createServer();
            this.undertow.start();
            this.state.set(HttpServerState.RUN);
            final int port = this.port();
            var data = StructuredArgument.marker("port", port);
            logger.info(data, "HTTP Server {} (Undertow) started on port {} in {}", name, port, TimeUtils.tookForLogging(started));
        } catch (Exception e) {
            if (e.getCause() instanceof BindException be) {
                throw new IllegalStateException("HTTP server '%s' (Undertow) failed to start on port '%s': port is already in use; stop the other process or configure a different port".formatted(name, httpServerConfig.get().port()), be);
            } else {
                throw new IllegalStateException("HTTP server '%s' (Undertow) failed to start on port '%s': %s; check server config, handler initialization, and network binding".formatted(name, httpServerConfig.get().port(), e.getMessage()), e);
            }
        }
    }

    @Override
    public void release() {
        logger.debug("Public HTTP Server (Undertow) stopping...");
        this.state.set(HttpServerState.SHUTDOWN);
        final long started = TimeUtils.started();
        this.gracefulShutdown.shutdown();
        final Duration shutdownAwait = this.httpServerConfig.get().shutdownWait();
        try {
            logger.debug("HTTP Server {} (Undertow) awaiting graceful shutdown...", this.name);
            if (!this.gracefulShutdown.awaitShutdown(shutdownAwait.toMillis())) {
                logger.warn("HTTP Server {} (Undertow) failed completing graceful shutdown in {}", this.name, shutdownAwait);
            }
        } catch (InterruptedException e) {
            logger.warn("HTTP Server {} (Undertow) failed completing graceful shutdown in {}", this.name, shutdownAwait, e);
        }

        if (this.undertow != null) {
            this.undertow.stop();
            this.undertow = null;
        }
        logger.info("HTTP Server {} (Undertow) stopped in {}", name, TimeUtils.tookForLogging(started));
    }

    private Undertow createServer() {
        var config = this.httpServerConfig.get();
        var undertow = Undertow.builder()
            .setHandler(this.gracefulShutdown)
            .addHttpListener(config.port(), "0.0.0.0")
            .setWorker(this.xnioWorker)
            .setIoThreads(undertowConfig.get().ioThreads())
            .setSocketOption(Options.READ_TIMEOUT, ((int) config.socketReadTimeout().toMillis()))
            .setSocketOption(Options.WRITE_TIMEOUT, ((int) config.socketWriteTimeout().toMillis()))
            .setSocketOption(Options.KEEP_ALIVE, config.socketKeepAliveEnabled())
            .setSocketOption(Options.TCP_NODELAY, true)
            .setServerOption(UndertowOptions.ENABLE_STATISTICS, false)
            .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, config.headerKeepAliveEnabled())
            .setServerOption(UndertowOptions.ALWAYS_SET_DATE, config.headerServerDateEnabled())
            .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, config.maxRequestBodySize().toBytes());

        if (this.configurer != null) {
            undertow = this.configurer.configure(undertow);
        }
        return undertow.build();
    }

    @Override
    public int port() {
        if (this.undertow == null) {
            return -1;
        }
        var infos = this.undertow.getListenerInfo();
        var address = (InetSocketAddress) infos.getFirst().getAddress();
        return address.getPort();
    }

    @Override
    public ReadinessProbeFailure probe() {
        return switch (this.state.get()) {
            case INIT -> new ReadinessProbeFailure("HTTP Server " + this.name + " (Undertow) init");
            case RUN -> null;
            case SHUTDOWN -> new ReadinessProbeFailure("HTTP Server " + this.name + " (Undertow) shutdown");
        };
    }

    private enum HttpServerState {
        INIT, RUN, SHUTDOWN
    }
}
