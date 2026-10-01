package io.koraframework.http.server.netty.handler;

import io.netty.channel.ChannelHandlerContext;

@FunctionalInterface
public interface NettyHttpHandler {

    void handle(ChannelHandlerContext ctx, Object msg) throws Exception;

    default void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
    }
}
