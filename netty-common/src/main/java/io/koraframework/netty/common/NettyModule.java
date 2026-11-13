package io.koraframework.netty.common;

import io.koraframework.application.graph.*;
import io.koraframework.common.annotation.*;
import io.koraframework.config.common.*;
import io.koraframework.config.common.mapper.*;
import io.koraframework.netty.common.NettyTransportConfig.*;
import io.koraframework.netty.common.loom.*;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.kqueue.*;
import io.netty.channel.nio.*;
import io.netty.channel.socket.nio.*;
import io.netty.channel.uring.*;
import io.netty.util.*;
import io.netty.util.concurrent.*;
import org.jspecify.annotations.*;

import java.util.concurrent.*;

public interface NettyModule {

    final class EventLoopBoss {
        private EventLoopBoss() {}
    }

    final class EventLoopWorker {
        private EventLoopWorker() {}
    }

    default NettyTransportConfig nettyTransportConfig(Config config, ConfigValueMapper<NettyTransportConfig> mapper) {
        return mapper.mapOrThrow(config.get("netty"));
    }

    default LoomCarrierConfig loomCarrierConfig(Config config, ConfigValueMapper<LoomCarrierConfig> mapper) {
        return mapper.mapOrThrow(config.get("netty.loom"));
    }

    @Tag(EventLoopBoss.class)
    @DefaultComponent
    default NettyEventLoopFactory nettyEventLoopBossFactory(@Tag(EventLoopBoss.class) @Nullable ThreadFactory threadFactory,
                                                            NettyTransportConfig config) {
        var tf = (threadFactory == null) ? new DefaultThreadFactory("netty-kora-boss", true) : threadFactory;
        return getNettyEventLoopFactory(1, tf, config);
    }

    @Tag(EventLoopBoss.class)
    @DefaultComponent
    default LifecycleWrapper<EventLoopGroup> nettyEventLoopBossGroup(@Tag(EventLoopBoss.class) NettyEventLoopFactory nettyEventLoopFactory) {
        return new LifecycleWrapper<>(
            nettyEventLoopFactory.build(),
            elg -> {},
            // we don't have to wait because graph will shutdown loop after all the dependent components
            elg -> {
                elg.shutdownGracefully(1, 1, TimeUnit.MILLISECONDS).get();
            }
        );
    }

    @DefaultComponent
    default ThreadFactory koraVirtualThreadFactory(
        LoomCarrierConfig loomConfig,
        EventLoopLoomFactory eventLoopLoomFactory) {

        return eventLoopLoomFactory.createVirtualThreadFactory(
            "kora-virtual-executor-",
            true,
            loomConfig.enabled()
        );
    }

    @DefaultComponent
    default EventLoopLoomFactory eventLoopLoomFactory(LoomCarrierConfig config) {
        return new EventLoopLoomFactory(config.enabled());
    }

    @DefaultComponent
    default EventLoopGroupFactory eventLoopGroupFactory(LoomCarrierConfig loomConfig, EventLoopLoomFactory eventLoopLoomFactory) {
        return new EventLoopGroupFactory(loomConfig, eventLoopLoomFactory);
    }

    @Tag(EventLoopWorker.class)
    @DefaultComponent
    default NettyEventLoopFactory nettyEventLoopWorkerFactory(
        @Tag(EventLoopWorker.class) @Nullable ThreadFactory threadFactory,
        NettyTransportConfig config,
        LoomCarrierConfig loomConfig,
        EventLoopGroupFactory eventLoopGroupFactory) {

        int nThreads = config.threads() > 0 ? config.threads() : NettyRuntime.availableProcessors();
        var ioHandlerFactory = getIoHandlerFactory(config);
        if (loomConfig.enabled()) {
            return () -> eventLoopGroupFactory.createEventLoopGroup(nThreads, "netty-kora-loom-worker", true, ioHandlerFactory);
        }

        var tf = (threadFactory == null) ? new DefaultThreadFactory("netty-kora-worker", true) : threadFactory;
        return getNettyEventLoopFactory(nThreads, tf, config);
    }

    @Tag(EventLoopWorker.class)
    @DefaultComponent
    default LifecycleWrapper<EventLoopGroup> nettyEventLoopWorkerGroup(@Tag(EventLoopWorker.class) NettyEventLoopFactory nettyEventLoopFactory) {
        return new LifecycleWrapper<>(
            nettyEventLoopFactory.build(),
            elg -> {},
            // we don't have to wait because graph will shutdown loop after all the dependent components
            elg -> elg.shutdownGracefully(1, 1, TimeUnit.MILLISECONDS).get()
        );
    }

    @DefaultComponent
    default NettyChannelFactory nettyChannelFactory(NettyTransportConfig nettyConfig) {
        if (nettyConfig.transport() != null) {
            if (EventLoopType.URING == nettyConfig.transport() && isUringAvailable()) {
                return getUringChannelFactory();
            } else if (EventLoopType.EPOLL == nettyConfig.transport() && isEpollAvailable()) {
                return getEpollChannelFactory();
            } else if (EventLoopType.KQUEUE == nettyConfig.transport() && isKQueueAvailable()) {
                return getKQueueChannelFactory();
            } else if (EventLoopType.NIO == nettyConfig.transport()) {
                return getNioChannelFactory();
            }
        }

        // default behavior
        if (isUringAvailable()) {
            return getUringChannelFactory();
        } else if (isEpollAvailable()) {
            return getEpollChannelFactory();
        } else if (isKQueueAvailable()) {
            return getKQueueChannelFactory();
        } else {
            return getNioChannelFactory();
        }
    }

    private static IoHandlerFactory getIoHandlerFactory(NettyTransportConfig config) {
        var transport = config.transport();
        if (transport != null) {
            if (transport == NettyTransportConfig.EventLoopType.URING && isUringAvailable()) {
                return IoUringIoHandler.newFactory();
            } else if (transport == NettyTransportConfig.EventLoopType.EPOLL && isEpollAvailable()) {
                return EpollIoHandler.newFactory();
            } else if (transport == NettyTransportConfig.EventLoopType.KQUEUE && isKQueueAvailable()) {
                return KQueueIoHandler.newFactory();
            } else if (transport == NettyTransportConfig.EventLoopType.NIO) {
                return NioIoHandler.newFactory();
            }
        }

        if (isUringAvailable()) {
            return IoUringIoHandler.newFactory();
        } else if (isEpollAvailable()) {
            return EpollIoHandler.newFactory();
        } else if (isKQueueAvailable()) {
            return KQueueIoHandler.newFactory();
        } else {
            return NioIoHandler.newFactory();
        }
    }


    private static NettyEventLoopFactory getNettyEventLoopFactory(int nThreads,
                                                                  ThreadFactory threadFactory,
                                                                  NettyTransportConfig config) {
        var ioHandlerFactory = getIoHandlerFactory(config);
        return () -> new MultiThreadIoEventLoopGroup(nThreads, threadFactory, ioHandlerFactory);
    }

    private static NettyChannelFactory getNioChannelFactory() {
        return new NettyChannelFactory() {
            @Override
            public ChannelFactory<Channel> build(boolean domainSocket) {
                if (domainSocket) {
                    return NioDomainSocketChannel::new;
                } else {
                    return NioSocketChannel::new;
                }
            }

            @Override
            public ChannelFactory<ServerChannel> buildServer(boolean domainSocket) {
                if (domainSocket) {
                    return NioServerDomainSocketChannel::new;
                } else {
                    return NioServerSocketChannel::new;
                }
            }
        };
    }

    private static NettyChannelFactory getEpollChannelFactory() {
        return new NettyChannelFactory() {
            @Override
            public ChannelFactory<Channel> build(boolean domainSocket) {
                if (domainSocket) {
                    return EpollDomainSocketChannel::new;
                } else {
                    return EpollSocketChannel::new;
                }
            }

            @Override
            public ChannelFactory<ServerChannel> buildServer(boolean domainSocket) {
                if (domainSocket) {
                    return EpollServerDomainSocketChannel::new;
                } else {
                    return EpollServerSocketChannel::new;
                }
            }
        };
    }

    private static NettyChannelFactory getKQueueChannelFactory() {
        return new NettyChannelFactory() {
            @Override
            public ChannelFactory<Channel> build(boolean domainSocket) {
                if (domainSocket) {
                    return KQueueDomainSocketChannel::new;
                } else {
                    return KQueueSocketChannel::new;
                }
            }

            @Override
            public ChannelFactory<ServerChannel> buildServer(boolean domainSocket) {
                if (domainSocket) {
                    return KQueueServerDomainSocketChannel::new;
                } else {
                    return KQueueServerSocketChannel::new;
                }
            }
        };
    }

    private static NettyChannelFactory getUringChannelFactory() {
        return new NettyChannelFactory() {
            @Override
            public ChannelFactory<Channel> build(boolean domainSocket) {
                if (domainSocket) {
                    return IoUringDomainSocketChannel::new;
                } else {
                    return IoUringSocketChannel::new;
                }
            }

            @Override
            public ChannelFactory<ServerChannel> buildServer(boolean domainSocket) {
                if (domainSocket) {
                    return IoUringServerDomainSocketChannel::new;
                } else {
                    return IoUringServerSocketChannel::new;
                }
            }
        };
    }

    private static boolean isEpollAvailable() {
        return isClassPresent("io.netty.channel.epoll.Epoll") && Epoll.isAvailable();
    }

    private static boolean isKQueueAvailable() {
        return isClassPresent("io.netty.channel.kqueue.KQueue") && KQueue.isAvailable();
    }

    private static boolean isUringAvailable() {
        return isClassPresent("io.netty.channel.uring.IoUring") && IoUring.isAvailable();
    }

    private static boolean isClassPresent(String className) {
        try {
            return NettyModule.class.getClassLoader().loadClass(className) != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
