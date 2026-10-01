package io.koraframework.http.server.netty;

import io.koraframework.application.graph.Lifecycle;
import io.koraframework.application.graph.ValueOf;
import io.koraframework.application.graph.Wrapped;
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
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class NettyResourceLifecycle implements Lifecycle, Wrapped<NettyResourceLifecycle.NettyResources> {

    private static final Logger log = LoggerFactory.getLogger(NettyResourceLifecycle.class);

    private final ValueOf<NettyConfig> configValue;
    private NettyResources resources;

    public NettyResourceLifecycle(ValueOf<NettyConfig> configValue) {
        this.configValue = configValue;
    }

    @Override
    public void init() {
        IoHandlerFactory factory;
        Class<? extends ServerChannel> channelClass;

        if (IoUring.isAvailable()) {
            factory = IoUringIoHandler.newFactory();
            channelClass = IoUringServerSocketChannel.class;
            log.info("Using io_uring Netty transport");
        } else if (Epoll.isAvailable()) {
            factory = EpollIoHandler.newFactory();
            channelClass = EpollServerSocketChannel.class;
            log.info("Using Epoll Netty transport");
        } else if (KQueue.isAvailable()) {
            factory = KQueueIoHandler.newFactory();
            channelClass = KQueueServerSocketChannel.class;
            log.info("Using KQueue Netty transport");
        } else {
            factory = NioIoHandler.newFactory();
            channelClass = NioServerSocketChannel.class;
            log.info("Using NIO Netty transport");
        }

        int threads = configValue.get().ioThreads();
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, factory);
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(threads, factory);

        this.resources = new NettyResources(bossGroup, workerGroup, channelClass);
    }

    @Override
    public void release() {
        if (resources != null) {
            log.debug("Shutting down Netty EventLoopGroups...");
            var config = configValue.get();
            long timeout = config.threadKeepAliveTimeout().toSeconds();

            Future<?> bossFuture = resources.bossGroup().shutdownGracefully(2, timeout, TimeUnit.SECONDS);
            Future<?> workerFuture = resources.workerGroup().shutdownGracefully(2, timeout, TimeUnit.SECONDS);

            bossFuture.awaitUninterruptibly();
            workerFuture.awaitUninterruptibly();
            log.info("Netty EventLoopGroups stopped smoothly.");
        }
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
