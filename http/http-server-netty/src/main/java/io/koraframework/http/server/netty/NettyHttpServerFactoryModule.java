package io.koraframework.http.server.netty;

import io.koraframework.application.graph.ValueOf;
import io.koraframework.common.Configurer;
import io.koraframework.common.annotation.DefaultComponent;
import io.koraframework.common.annotation.Root;
import io.koraframework.common.annotation.Tag;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.HttpServerFactoryModule;
import io.koraframework.http.server.common.router.HttpServerRouter;
import io.koraframework.http.server.common.telemetry.HttpServerTelemetryFactory;
import io.koraframework.http.server.netty.handler.KoraHttpServerHandler;
import io.koraframework.http.server.netty.handler.NettyHttpHandler;
import io.netty.bootstrap.ServerBootstrap;
import org.jspecify.annotations.Nullable;

public class NettyHttpServerFactoryModule extends HttpServerFactoryModule {

    private final String name;

    public NettyHttpServerFactoryModule(String name, String configPath) {
        super(configPath);
        this.name = name;
    }

    @Root
    @Tag(Tag.Factory.class)
    public NettyHttpServer server(
        NettyResourceLifecycle.NettyResources resources,
        @Tag(Tag.Factory.class) ValueOf<NettyHttpHandler> httpHandler,
        @Tag(Tag.Factory.class) ValueOf<HttpServerConfig> httpServerConfig,
        @Tag(Tag.Factory.class) @Nullable Configurer<ServerBootstrap> configurer) {
        return new NettyHttpServer(this.name, resources, httpServerConfig, httpHandler, configurer);
    }

    @DefaultComponent
    @Tag(Tag.Factory.class)
    public NettyHttpHandler handler(
        @Tag(Tag.Factory.class) HttpServerConfig httpServerConfig,
        @Tag(Tag.Factory.class) HttpServerRouter httpServerRouter,
        HttpServerTelemetryFactory telemetryFactory) {
        var telemetry = telemetryFactory.get(this.name, httpServerConfig.port(), httpServerConfig.telemetry());
        return new KoraHttpServerHandler(httpServerConfig, httpServerRouter, telemetry);
    }
}
