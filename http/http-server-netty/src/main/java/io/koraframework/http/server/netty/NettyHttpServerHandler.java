package io.koraframework.http.server.netty;

import io.koraframework.http.server.common.request.HttpServerRequestHandler;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.netty.request.NettyHttpServerRequest;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class NettyHttpServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServerHandler.class);

    private final HttpServerRequestHandler rootHandler;

    public NettyHttpServerHandler(HttpServerRequestHandler rootHandler) {
        this.rootHandler = rootHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest nettyReq) {
            nettyReq.retain();
            var version = nettyReq.protocolVersion();
            var keepAlive = HttpUtil.isKeepAlive(nettyReq);
            var koraReq = new NettyHttpServerRequest(nettyReq);

            Thread.startVirtualThread(() -> {
                try {
                    HttpServerResponse koraResp = rootHandler.handle(koraReq);
                    sendSuccessResponse(ctx, version, keepAlive, koraResp);
                } catch (Throwable t) {
                    sendErrorResponse(ctx, version, keepAlive, t);
                } finally {
                    try {
                        koraReq.body().close();
                    } catch (IOException e) {
                        log.warn("Failed to close request body", e);
                    } finally {
                        nettyReq.release();
                    }
                }
            });
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void sendSuccessResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, HttpServerResponse koraResp) {
        var buffer = ctx.alloc().buffer();

        try (var bodyOutput = koraResp.body()) {
            if (bodyOutput != null) {
                try (var os = new ByteBufOutputStream(buffer)) {
                    bodyOutput.write(os);
                }
            }
        } catch (IOException e) {
            buffer.release();
            sendErrorResponse(ctx, version, keepAlive, e);
            return;
        }

        var nettyResp = new DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(koraResp.code()), buffer);

        koraResp.headers().forEach(entry -> {
            for (String val : entry.getValue()) {
                nettyResp.headers().add(entry.getKey(), val);
            }
        });
        nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

        if (keepAlive) {
            if (version.isKeepAliveDefault()) {
                nettyResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        } else {
            nettyResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        try {
            ctx.channel().eventLoop().execute(() -> {
                var future = ctx.writeAndFlush(nettyResp);
                if (!keepAlive) {
                    future.addListener(ChannelFutureListener.CLOSE);
                }
            });
        } catch (Throwable t) {
            nettyResp.release();
            log.error("Failed to enqueue response write task", t);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Throwable t) {
        log.error("Error processing request in Kora", t);
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

        if (keepAlive) {
            errorResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            errorResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        try {
            ctx.channel().eventLoop().execute(() -> {
                var future = ctx.writeAndFlush(errorResp);
                if (!keepAlive) {
                    future.addListener(ChannelFutureListener.CLOSE);
                }
            });
        } catch (Throwable e) {
            errorResp.release();
            log.error("Failed to enqueue error response write task", e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty pipeline exception caught", cause);
        ctx.close();
    }
}
