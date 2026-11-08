package io.koraframework.http.server.netty;

import io.koraframework.application.graph.LifecycleWrapper;
import io.koraframework.application.graph.Wrapped;
import io.koraframework.common.annotation.DefaultComponent;
import io.koraframework.common.annotation.FactoryModule;
import io.koraframework.common.annotation.Tag;
import io.koraframework.config.common.Config;
import io.koraframework.config.common.mapper.ConfigValueMapper;
import io.koraframework.http.server.common.system.SystemApi;
import io.koraframework.http.server.common.system.SystemHttpServerModule;
import io.koraframework.netty.common.NettyChannelFactory;
import io.koraframework.netty.common.NettyModule.EventLoopBoss;
import io.koraframework.netty.common.NettyModule.EventLoopWorker;
import io.koraframework.netty.common.NettyTransportConfig;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;

public interface NettySystemHttpServerModule extends SystemHttpServerModule {

    @FactoryModule
    @SystemApi
    default NettyHttpServerFactoryModule nettySystemHttpApi() {
        return new NettyHttpServerFactoryModule("kora-netty-system", "httpServer.system");
    }

    default NettyTransportConfig nettyTransportConfig(Config config, ConfigValueMapper<NettyTransportConfig> mapper) {
        return mapper.mapOrThrow(config.get("netty"));
    }

    @DefaultComponent
    default Wrapped<NettyResources> nettyResources(
        @Tag(EventLoopBoss.class) LifecycleWrapper<EventLoopGroup> bossGroup,
        @Tag(EventLoopWorker.class) LifecycleWrapper<EventLoopGroup> workerGroup,
        NettyChannelFactory channelFactory
    ) {
        var serverChannelFactory = channelFactory.buildServer(false);
        var temporaryChannel = serverChannelFactory.newChannel();
        var channelClass = temporaryChannel.getClass();
        temporaryChannel.close();
        var resources = new NettyResources(bossGroup.value(), workerGroup.value(), channelClass);
        return () -> resources;
    }

    record NettyResources(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerChannel> channelClass
    ) {}
}
