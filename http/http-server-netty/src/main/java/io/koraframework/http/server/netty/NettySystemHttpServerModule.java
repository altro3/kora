package io.koraframework.http.server.netty;

import io.koraframework.application.graph.ValueOf;
import io.koraframework.application.graph.Wrapped;
import io.koraframework.common.annotation.DefaultComponent;
import io.koraframework.common.annotation.FactoryModule;
import io.koraframework.config.common.Config;
import io.koraframework.config.common.mapper.ConfigValueMapper;
import io.koraframework.http.server.common.system.SystemApi;
import io.koraframework.http.server.common.system.SystemHttpServerModule;

public interface NettySystemHttpServerModule extends SystemHttpServerModule {

    @FactoryModule
    @SystemApi
    default NettyHttpServerFactoryModule nettySystemHttpApi() {
        return new NettyHttpServerFactoryModule("kora-netty-system", "httpServer.system");
    }

    default NettyConfig nettyHttpServerConfig(Config config, ConfigValueMapper<NettyConfig> mapper) {
        return mapper.mapOrThrow(config.get("httpServer.netty"));
    }

    @DefaultComponent
    default Wrapped<NettyResourceLifecycle.NettyResources> nettyResources(ValueOf<NettyConfig> configValue) {
        return new NettyResourceLifecycle(configValue);
    }
}
