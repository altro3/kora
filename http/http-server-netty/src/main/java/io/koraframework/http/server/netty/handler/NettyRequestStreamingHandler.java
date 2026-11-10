package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.*;
import io.koraframework.http.server.netty.request.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.*;
import io.netty.util.*;

import java.io.*;
import java.net.*;

public final class NettyRequestStreamingHandler extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<NettyRequestHttpBody> ACTIVE_BODY_KEY = AttributeKey.valueOf("kora.active.body");

    private final HttpServerConfig config;

    public NettyRequestStreamingHandler(HttpServerConfig config) {
        this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req) {
            if (req instanceof FullHttpRequest fullReq) {
                ctx.channel().attr(ACTIVE_BODY_KEY).set(null);
                ctx.fireChannelRead(fullReq);
                return;
            }

            var body = new NettyRequestHttpBody(req, ctx, config.maxRequestBodySize().toBytes());
            ctx.channel().attr(ACTIVE_BODY_KEY).set(body);
            ctx.fireChannelRead(req);
            return;
        }

        if (msg instanceof HttpContent content) {
            NettyRequestHttpBody body = ctx.channel().attr(ACTIVE_BODY_KEY).get();
            if (body != null) {
                body.addContent(content);
                if (content instanceof LastHttpContent) {
                    ctx.fireChannelRead(content);
                    return;
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
    public void channelInactive(ChannelHandlerContext ctx) {
        NettyRequestHttpBody body = ctx.channel().attr(ACTIVE_BODY_KEY).getAndSet(null);
        if (body != null) {
            body.setException(new IOException("Connection closed prematurely"));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof ReadTimeoutException) {
            NettyRequestHttpBody body = ctx.channel().attr(ACTIVE_BODY_KEY).getAndSet(null);
            if (body != null) {
                body.setException(new SocketTimeoutException("Read timeout"));
            }
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
