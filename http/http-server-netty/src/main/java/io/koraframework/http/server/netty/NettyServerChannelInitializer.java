package io.koraframework.http.server.netty;

import io.koraframework.application.graph.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.netty.handler.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.*;
import io.netty.handler.timeout.*;

import java.util.concurrent.*;

public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final HttpDecoderConfig DECODER_CONFIG = new HttpDecoderConfig()
        .setMaxInitialLineLength(4096)
        .setMaxHeaderSize(8192);

    private final NettyHttpServer server;
    private final HttpServerConfig config;
    private final ValueOf<KoraVirtualThreadHandler> koraVirtualThreadHandlerValue;

    public NettyServerChannelInitializer(NettyHttpServer server, HttpServerConfig config, ValueOf<KoraVirtualThreadHandler> koraVirtualThreadHandlerValue) {
        this.server = server;
        this.config = config;
        this.koraVirtualThreadHandlerValue = koraVirtualThreadHandlerValue;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();

        p.addLast("http-codec", new HttpServerCodec(DECODER_CONFIG));
        p.addLast(new HttpObjectAggregator((int) config.maxRequestBodySize().toBytes()));
        var readTimeout = config.socketReadTimeout();
        if (!readTimeout.isZero()) {
            p.addLast("read-timeout", new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }
        var writeTimeout = config.socketWriteTimeout();
        if (!writeTimeout.isZero()) {
            p.addLast("write-timeout", new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }
        p.addLast("virtual-thread-handler", koraVirtualThreadHandlerValue.get());

        //        p.addLast("cpu-benchmark-pong", new ChannelInboundHandlerAdapter() {
//            @Override
//            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//                if (msg instanceof HttpRequest req) {
//                    var content = io.netty.buffer.Unpooled.copiedBuffer("OK", java.nio.charset.StandardCharsets.UTF_8);
//                    FullHttpResponse response = new DefaultFullHttpResponse(
//                        HttpVersion.HTTP_1_1,
//                        HttpResponseStatus.OK,
//                        content
//                    );
//                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
//                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
//                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
//
//                    ctx.writeAndFlush(response);
//                    io.netty.util.ReferenceCountUtil.release(msg);
//                } else {
//                    io.netty.util.ReferenceCountUtil.release(msg);
//                }
//            }
//        });

    }
}
