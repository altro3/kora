package io.koraframework.http.server.netty;

import io.koraframework.common.annotation.FactoryModule;

public interface NettyPublicHttpServerModule extends NettySystemHttpServerModule {

    @FactoryModule
    default NettyHttpServerFactoryModule nettyPublicHttpApi() {
        return new NettyHttpServerFactoryModule("kora-netty", "httpServer");
    }
}
