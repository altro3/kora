package io.koraframework.http.server.netty;

import io.koraframework.application.graph.ValueOf;
import io.koraframework.common.Configurer;
import io.koraframework.common.annotation.Root;
import io.koraframework.common.annotation.Tag;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.HttpServerFactoryModule;
import io.koraframework.http.server.common.request.HttpServerRequestHandler;
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
        @Tag(Tag.Factory.class) ValueOf<HttpServerConfig> httpServerConfig,
        HttpServerRequestHandler rootHandler,
        @Tag(Tag.Factory.class) @Nullable Configurer<ServerBootstrap> configurer
    ) {
        return new NettyHttpServer(this.name, resources, httpServerConfig, rootHandler, configurer);
    }
}
