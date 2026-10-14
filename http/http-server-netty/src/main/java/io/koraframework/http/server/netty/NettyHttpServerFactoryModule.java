package io.koraframework.http.server.netty;

import io.koraframework.application.graph.*;
import io.koraframework.common.*;
import io.koraframework.common.annotation.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.router.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.NettySystemHttpServerModule.*;
import io.koraframework.http.server.netty.handler.*;
import io.netty.bootstrap.*;
import org.jspecify.annotations.*;

public class NettyHttpServerFactoryModule extends HttpServerFactoryModule {

    private final String name;

    public NettyHttpServerFactoryModule(String name, String configPath) {
        super(configPath);
        this.name = name;
    }

    @Root
    @Tag(Tag.Factory.class)
    public NettyHttpServer server(
        NettyResources resources,
        @Tag(Tag.Factory.class) ValueOf<HttpServerConfig> httpServerConfig,
        HttpServerRouter httpServerRouter,
        @Tag(Tag.Factory.class) ValueOf<KoraVirtualThreadDispatcher> virtualThreadDispatcher,
        @Tag(Tag.Factory.class) ValueOf<NettyResponseWriteHandler> responseWriteHandler,
        @Tag(Tag.Factory.class) @Nullable ValueOf<NettyTelemetryHandler> telemetryHandler,
        @Tag(Tag.Factory.class) @Nullable Configurer<ServerBootstrap> configurer
    ) {
        return new NettyHttpServer(
            this.name,
            resources,
            httpServerConfig,
            httpServerRouter,
            virtualThreadDispatcher,
            responseWriteHandler,
            telemetryHandler,
            configurer
        );
    }

    @DefaultComponent
    @Tag(Tag.Factory.class)
    public KoraVirtualThreadDispatcher virtualThreadDispatcher(
        @Tag(Tag.Factory.class) HttpServerConfig httpServerConfig
    ) {
        return new KoraVirtualThreadDispatcher(httpServerConfig, httpServerConfig.telemetry().logging().enabled());
    }

    @DefaultComponent
    @Tag(Tag.Factory.class)
    public NettyResponseWriteHandler responseWriteHandler(
        @Tag(Tag.Factory.class) HttpServerConfig httpServerConfig
    ) {
        return new NettyResponseWriteHandler(httpServerConfig);
    }

    @DefaultComponent
    @Tag(Tag.Factory.class)
    public @Nullable NettyTelemetryHandler telemetryHandler(
        @Tag(Tag.Factory.class) HttpServerConfig httpServerConfig,
        @Tag(Tag.Factory.class) HttpServerRouter httpServerRouter,
        HttpServerTelemetryFactory telemetryFactory
    ) {
        if (!httpServerConfig.telemetry().logging().enabled() && !httpServerConfig.telemetry().metrics().enabled() && !httpServerConfig.telemetry().tracing().enabled()) {
            return null;
        }
        var telemetry = telemetryFactory.get(this.name, httpServerConfig.port(), httpServerConfig.telemetry());
        return new NettyTelemetryHandler(telemetry, httpServerRouter);
    }
}
