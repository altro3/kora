package io.koraframework.http.server.netty;

import io.koraframework.application.graph.Lifecycle;
import io.koraframework.common.annotation.Component;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.request.HttpServerRequestHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
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
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public final class NettyHttpServer implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final HttpServerConfig config;
    private final HttpServerRequestHandler rootHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyHttpServer(HttpServerConfig config, HttpServerRequestHandler rootHandler) {
        this.config = config;
        this.rootHandler = rootHandler;
    }

    @Override
    public void init() throws Exception {
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

        this.bossGroup = new MultiThreadIoEventLoopGroup(1, factory);
        this.workerGroup = new MultiThreadIoEventLoopGroup(Runtime.getRuntime().availableProcessors(), factory);

        var b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(channelClass)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.SO_RCVBUF, 16384)
            .childOption(ChannelOption.SO_SNDBUF, 16384)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(new HttpServerCodec());
                    p.addLast(new HttpObjectAggregator((int) config.maxRequestBodySize().toBytes()));
                    p.addLast(new NettyHttpServerHandler(rootHandler));
                }
            });

        int port = config.port();
        this.serverChannel = b.bind(port).sync().channel();
        log.info("Netty HTTP Server started on port: {}", port);
    }


    @Override
    public void release() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Netty HTTP Server stopped");
    }
}
