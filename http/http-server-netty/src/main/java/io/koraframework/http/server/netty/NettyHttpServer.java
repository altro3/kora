package io.koraframework.http.server.netty;

import io.koraframework.application.graph.Lifecycle;
import io.koraframework.common.annotation.Component;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.common.request.HttpServerRequestHandler;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.netty.request.NettyHttpServerRequest;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;

import java.nio.channels.Channel;

@Component
public final class NettyHttpServer implements Lifecycle {
    private final HttpServerConfig config;
    private final io.koraframework.http.server.common.request.HttpServerRequestHandler rootHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyHttpServer(HttpServerConfig config, HttpServerRequestHandler rootHandler) {
        this.config = config;
        this.rootHandler = rootHandler;
    }

    @Override
    public void start() throws Exception {
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());

        var b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
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
                 p.addLast(new KoraRequestHandler());
             }
         });

        int port = config.port();
        this.serverChannel = b.bind(port).sync().channel();
    }

    @Override
    public void stop() {
        if (serverChannel != null) serverChannel.close().syncUninterruptibly();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }

    private class KoraRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest nettyReq) {
            var koraReq = new NettyHttpServerRequest(nettyReq);

            Thread.startVirtualThread(() -> {
                try {
                    HttpServerResponse koraResp = rootHandler.handle(koraReq).join();
                    byte[] bodyBytes = koraResp.body() != null ? koraResp.body() : new byte[0];
                    
                    var nettyResp = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(koraResp.code()),
                            Unpooled.wrappedBuffer(bodyBytes)
                    );

                    koraResp.headers().forEach(entry -> 
                        nettyResp.headers().set(entry.getKey(), entry.getValue())
                    );
                    nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);

                    ctx.writeAndFlush(nettyResp);
                } catch (Exception e) {
                    var errorResp = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, 
                            HttpResponseStatus.INTERNAL_SERVER_ERROR
                    );
                    ctx.writeAndFlush(errorResp);
                }
            });
        }
    }
}
