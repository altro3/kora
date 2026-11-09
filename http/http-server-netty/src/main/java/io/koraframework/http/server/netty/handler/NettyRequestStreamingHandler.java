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
    private NettyRequestHttpBody activeBody;

    public NettyRequestStreamingHandler(HttpServerConfig config) {
        this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest req) {
            if (req instanceof FullHttpRequest fullReq) {
                this.activeBody = null;
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
            this.activeBody = new NettyRequestHttpBody(fullReq, ctx, config.maxRequestBodySize().toBytes());
            ctx.channel().attr(ACTIVE_BODY_KEY).set(activeBody);
            ReferenceCountUtil.release(req);
            ctx.fireChannelRead(fullReq);
            return;
        }

        if (msg instanceof HttpContent content) {
            NettyRequestHttpBody body = this.activeBody;
            if (body != null) {
                body.addContent(content);
                if (content instanceof LastHttpContent) {
                    this.activeBody = null;
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
        if (activeBody != null) {
            activeBody.setException(new IOException("Connection closed prematurely"));
            this.activeBody = null;
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof io.netty.handler.timeout.ReadTimeoutException) {
            if (activeBody != null) {
                activeBody.setException(new java.net.SocketTimeoutException("Read timeout"));
                this.activeBody = null;
            }
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
