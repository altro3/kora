package io.koraframework.http.server.netty;

import io.koraframework.http.server.common.request.HttpServerRequestHandler;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.netty.request.NettyHttpServerRequest;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NettyHttpServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(NettyHttpServerHandler.class);

    private final HttpServerRequestHandler rootHandler;

    public NettyHttpServerHandler(HttpServerRequestHandler rootHandler) {
        this.rootHandler = rootHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest nettyReq) {
            var koraReq = new NettyHttpServerRequest(nettyReq);

            Thread.startVirtualThread(() -> {
                try {
                    HttpServerResponse koraResp = rootHandler.handle(koraReq);
                    sendSuccessResponse(ctx, koraResp);
                } catch (Throwable t) {
                    sendErrorResponse(ctx, t);
                } finally {
                    try {
                        koraReq.body().close();
                    } catch (java.io.IOException e) {
                        log.warn("Failed to close request body", e);
                    }
                }
            });
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void sendSuccessResponse(ChannelHandlerContext ctx, HttpServerResponse koraResp) {
        byte[] bodyBytes = koraResp.body() != null ? koraResp.body() : new byte[0];
        var nettyResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(koraResp.code()),
                Unpooled.wrappedBuffer(bodyBytes)
        );

        koraResp.headers().forEach(entry -> {
            for (String val : entry.getValue()) {
                nettyResp.headers().add(entry.getKey(), val);
            }
        });
        nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);

        ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(nettyResp));
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, Throwable t) {
        log.error("Error processing request in Kora", t);
        var errorResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.INTERNAL_SERVER_ERROR
        );
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(errorResp));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty pipeline exception caught", cause);
        ctx.close();
    }
}
