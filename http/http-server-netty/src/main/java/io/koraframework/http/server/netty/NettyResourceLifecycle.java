package io.koraframework.http.server.netty;

import io.koraframework.application.graph.Lifecycle;
import io.koraframework.application.graph.ValueOf;
import io.koraframework.application.graph.Wrapped;
import io.koraframework.common.util.TimeUtils;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class NettyResourceLifecycle implements Lifecycle, Wrapped<NettyResourceLifecycle.NettyResources> {

    private static final Logger log = LoggerFactory.getLogger(NettyResourceLifecycle.class);

    private final ValueOf<NettyConfig> configValue;
    private volatile NettyResources resources;

    public NettyResourceLifecycle(ValueOf<NettyConfig> configValue) {
        this.configValue = configValue;
    }

    @Override
    public void init() {
        log.debug("Netty EventLoopGroups starting...");
        var started = System.nanoTime();

        final IoHandlerFactory bossFactory;
        final IoHandlerFactory workerFactory;
        final Class<? extends ServerChannel> channelClass;

        if (IoUring.isAvailable()) {
            bossFactory = IoUringIoHandler.newFactory();
            workerFactory = IoUringIoHandler.newFactory();
            channelClass = IoUringServerSocketChannel.class;
        } else if (Epoll.isAvailable()) {
            bossFactory = EpollIoHandler.newFactory();
            workerFactory = EpollIoHandler.newFactory();
            channelClass = EpollServerSocketChannel.class;
        } else if (KQueue.isAvailable()) {
            bossFactory = KQueueIoHandler.newFactory();
            workerFactory = KQueueIoHandler.newFactory();
            channelClass = KQueueServerSocketChannel.class;
        } else {
            bossFactory = NioIoHandler.newFactory();
            workerFactory = NioIoHandler.newFactory();
            channelClass = NioServerSocketChannel.class;
        }

        int threads = configValue.get().ioThreads();

        var bossGroup = new MultiThreadIoEventLoopGroup(
            1,
            new DefaultThreadFactory("kora-netty-boss", false),
            bossFactory
        );

        var workerGroup = new MultiThreadIoEventLoopGroup(
            threads,
            new DefaultThreadFactory("kora-netty-worker", false),
            workerFactory
        );

        this.resources = new NettyResources(bossGroup, workerGroup, channelClass);

        log.info("Netty EventLoopGroups started using {} transport in {}",
            this.resources.channelClass().getSimpleName(),
            TimeUtils.tookForLogging(started));
    }

    @Override
    public void release() {
        if (resources == null) {
            return;
        }
        log.debug("Netty EventLoopGroups stopping...");
        var started = System.nanoTime();
        var config = configValue.get();

        long timeoutSec = config.threadKeepAliveTimeout().toSeconds();
        var bossFuture = resources.bossGroup().shutdownGracefully(2, timeoutSec, TimeUnit.SECONDS);
        var workerFuture = resources.workerGroup().shutdownGracefully(2, timeoutSec, TimeUnit.SECONDS);

        try {
            bossFuture.get(timeoutSec + 1, TimeUnit.SECONDS);
            workerFuture.get(timeoutSec + 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error while stopping Netty EventLoopGroups: {}", e.getMessage());
        }

        log.info("Netty EventLoopGroups stopped in {}", TimeUtils.tookForLogging(started));
    }

    @Override
    public NettyResources value() {
        return this.resources;
    }

    public record NettyResources(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerChannel> channelClass
    ) {}
}
