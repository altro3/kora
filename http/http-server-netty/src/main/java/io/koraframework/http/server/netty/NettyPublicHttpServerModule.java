package io.koraframework.http.server.netty;

import io.koraframework.common.annotation.FactoryModule;
import io.koraframework.config.common.*;
import io.koraframework.config.common.mapper.*;
import io.koraframework.netty.common.*;

public interface NettyPublicHttpServerModule extends NettySystemHttpServerModule, NettyModule {

    default KoraNettyServerConfig koraNettyServerConfig(Config config, ConfigValueMapper<KoraNettyServerConfig> mapper) {
        return mapper.mapOrThrow(config.get("netty.server"));
    }

    @FactoryModule
    default NettyHttpServerFactoryModule nettyPublicHttpApi() {
        return new NettyHttpServerFactoryModule("kora-netty", "httpServer");
    }
}
