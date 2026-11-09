package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.*;
import io.koraframework.http.server.netty.request.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;

import java.io.*;

public final class NettyRequestStreamingHandler extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<NettyRequestHttpBody> ACTIVE_BODY_KEY = AttributeKey.valueOf("kora.active.body");

    private final HttpServerConfig config;

    public NettyRequestStreamingHandler(HttpServerConfig config) {
        this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest req) {
            if (req instanceof FullHttpRequest fullReq) {
                ctx.channel().attr(ACTIVE_BODY_KEY).set(null);
                ctx.fireChannelRead(fullReq);
                return;
            }

            var fullReq = new DefaultFullHttpRequest(
                req.protocolVersion(),
                req.method(),
                req.uri(),
                Unpooled.EMPTY_BUFFER,
                req.headers(),
                EmptyHttpHeaders.INSTANCE
            );

            var body = new NettyRequestHttpBody(fullReq, ctx, config.maxRequestBodySize().toBytes());
            ctx.channel().attr(ACTIVE_BODY_KEY).set(body);

            ReferenceCountUtil.release(req);
            ctx.fireChannelRead(fullReq);
            return;
        }

        if (msg instanceof HttpContent content) {
            NettyRequestHttpBody body = ctx.channel().attr(ACTIVE_BODY_KEY).get();
            if (body != null) {
                body.addContent(content);
                if (content instanceof LastHttpContent) {
                    ctx.channel().attr(ACTIVE_BODY_KEY).set(null);
                }
                ReferenceCountUtil.release(content);
            } else {
                ctx.fireChannelRead(msg);
            }
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyRequestHttpBody body = ctx.channel().attr(ACTIVE_BODY_KEY).getAndSet(null);
        if (body != null) {
            body.setException(new IOException("Connection closed prematurely"));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof io.netty.handler.timeout.ReadTimeoutException) {
            NettyRequestHttpBody body = ctx.channel().attr(ACTIVE_BODY_KEY).getAndSet(null);
            if (body != null) {
                body.setException(new java.net.SocketTimeoutException("Read timeout"));
            }
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
