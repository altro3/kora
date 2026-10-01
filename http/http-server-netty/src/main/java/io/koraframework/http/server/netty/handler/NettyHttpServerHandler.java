package io.koraframework.http.server.netty.handler;

import io.koraframework.http.server.common.request.HttpServerRequestHandler;
import io.koraframework.http.server.common.response.HttpServerResponse;
import io.koraframework.http.server.netty.NettyContext;
import io.koraframework.http.server.netty.request.NettyHttpServerRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class NettyHttpServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServerHandler.class);

    private final HttpServerRequestHandler rootHandler;
    private final AtomicInteger activeRequests;
    private final Phaser phaser;
    private final BooleanSupplier shuttingDown;

    public NettyHttpServerHandler(
        HttpServerRequestHandler rootHandler,
        AtomicInteger activeRequests,
        Phaser phaser,
        BooleanSupplier shuttingDown) {
        this.rootHandler = rootHandler;
        this.activeRequests = activeRequests;
        this.phaser = phaser;
        this.shuttingDown = shuttingDown;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest nettyReq) {
            if (shuttingDown.getAsBoolean()) {
                sendServiceUnavailable(ctx, nettyReq);
                return;
            }

            activeRequests.incrementAndGet();
            phaser.register();

            var version = nettyReq.protocolVersion();
            var keepAlive = HttpUtil.isKeepAlive(nettyReq);
            var koraReq = new NettyHttpServerRequest(nettyReq);

            Thread.startVirtualThread(() -> {
                try {
                    ScopedValue.where(NettyContext.VALUE, new NettyContext(nettyReq)).run(() -> {
                        try {
                            HttpServerResponse koraResp = rootHandler.handle(koraReq);
                            sendSuccessResponse(ctx, version, keepAlive, koraResp);
                        } catch (Throwable t) {
                            sendErrorResponse(ctx, version, keepAlive, t);
                        }
                    });
                } finally {
                    try {
                        koraReq.body().close();
                    } catch (IOException e) {
                        log.warn("Failed to close request body", e);
                    } finally {
                        nettyReq.release();
                        activeRequests.decrementAndGet();
                        phaser.arriveAndDeregister();
                    }
                }
            });
        } else {
            ctx.fireChannelRead(msg);
        }
    }


    private void sendSuccessResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, HttpServerResponse koraResp) {
        ByteBuf buffer = ctx.alloc().buffer();

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

        setupConnectionHeader(nettyResp, version, keepAlive);

        var future = ctx.writeAndFlush(nettyResp);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, Throwable t) {
        log.error("Error processing request in Kora", t);
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

        setupConnectionHeader(errorResp, version, keepAlive);

        var future = ctx.writeAndFlush(errorResp);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void sendServiceUnavailable(ChannelHandlerContext ctx, FullHttpRequest nettyReq) {
        var version = nettyReq.protocolVersion();
        var errorResp = new DefaultFullHttpResponse(version, HttpResponseStatus.SERVICE_UNAVAILABLE);
        errorResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        errorResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(errorResp).addListener(ChannelFutureListener.CLOSE);
        ReferenceCountUtil.release(nettyReq);
    }

    private void setupConnectionHeader(HttpResponse response, HttpVersion version, boolean keepAlive) {
        if (keepAlive) {
            if (!version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        } else {
            if (version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty pipeline exception caught", cause);
        ctx.close();
    }
}
