package io.koraframework.http.server.netty;

import io.koraframework.common.annotation.FactoryModule;
import io.koraframework.netty.common.*;

public interface NettyPublicHttpServerModule extends NettySystemHttpServerModule, NettyModule {

    @FactoryModule
    default NettyHttpServerFactoryModule nettyPublicHttpApi() {
        return new NettyHttpServerFactoryModule("kora-netty", "httpServer");
    }
}
