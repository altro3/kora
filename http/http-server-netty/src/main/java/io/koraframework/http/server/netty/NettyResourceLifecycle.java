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
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class NettyResourceLifecycle implements Lifecycle, Wrapped<NettyResourceLifecycle.NettyResources> {

    private static final Logger log = LoggerFactory.getLogger(NettyResourceLifecycle.class);

    private final ValueOf<NettyConfig> configValue;
    private volatile NettyResources resources;

    public NettyResourceLifecycle(ValueOf<NettyConfig> configValue) {
        this.configValue = configValue;
    }

    @Override
    public void init() throws Exception {
        log.debug("Netty EventLoopGroups starting...");
        var started = System.nanoTime();

        var future = new CompletableFuture<NettyResources>();
        var thread = Thread.ofPlatform()
            .name("kora-netty-init")
            .daemon(false)
            .unstarted(() -> {
                try {
                    IoHandlerFactory factory;
                    Class<? extends ServerChannel> channelClass;

                    if (IoUring.isAvailable()) {
                        factory = IoUringIoHandler.newFactory();
                        channelClass = IoUringServerSocketChannel.class;
                    } else if (Epoll.isAvailable()) {
                        factory = EpollIoHandler.newFactory();
                        channelClass = EpollServerSocketChannel.class;
                    } else if (KQueue.isAvailable()) {
                        factory = KQueueIoHandler.newFactory();
                        channelClass = KQueueServerSocketChannel.class;
                    } else {
                        factory = NioIoHandler.newFactory();
                        channelClass = NioServerSocketChannel.class;
                    }

                    int threads = configValue.get().ioThreads();

                    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(
                        1,
                        new DefaultThreadFactory("kora-netty-boss", false),
                        factory
                    );
                    EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(
                        threads,
                        new DefaultThreadFactory("kora-netty-worker", false),
                        factory
                    );

                    future.complete(new NettyResources(bossGroup, workerGroup, channelClass));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });

        thread.start();
        this.resources = future.get();

        log.info("Netty EventLoopGroups started using {} transport in {}",
            this.resources.channelClass().getSimpleName(),
            TimeUtils.tookForLogging(started));
    }

    @Override
    public void release() {
        if (resources != null) {
            log.debug("Netty EventLoopGroups stopping...");
            var started = System.nanoTime();
            var config = configValue.get();
            long timeout = config.threadKeepAliveTimeout().toSeconds();

            Future<?> bossFuture = resources.bossGroup().shutdownGracefully(2, timeout, TimeUnit.SECONDS);
            Future<?> workerFuture = resources.workerGroup().shutdownGracefully(2, timeout, TimeUnit.SECONDS);

            bossFuture.awaitUninterruptibly();
            workerFuture.awaitUninterruptibly();
            log.info("Netty EventLoopGroups stopped in {}", TimeUtils.tookForLogging(started));
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
