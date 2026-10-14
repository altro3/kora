package io.koraframework.http.server.netty.handler;

import io.koraframework.http.common.body.HttpBodyOutput;
import io.koraframework.http.server.common.HttpServerConfig;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.ResponseContainer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@ChannelHandler.Sharable
public final class NettyResponseWriteHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(NettyResponseWriteHandler.class);

    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

    private final HttpServerConfig httpServerConfig;

    public NettyResponseWriteHandler(HttpServerConfig httpServerConfig) {
        this.httpServerConfig = httpServerConfig;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ResponseContainer container)) {
            ctx.fireChannelRead(msg);
            return;
        }

        var nettyReq = container.nettyReq();
        var version = nettyReq.protocolVersion();
        var keepAlive = HttpUtil.isKeepAlive(nettyReq);
        var response = container.koraResponse();
        boolean isHead = nettyReq.method() == HttpMethod.HEAD;

        var body = response.body();
        var content = body != null ? body.getFullContentIfAvailable() : null;

        if (body == null || isHead || content != null) {
            if (ctx.executor().inEventLoop()) {
                prepareAndSendResponse(ctx, version, keepAlive, response, isHead);
            } else {
                ctx.executor().execute(() -> prepareAndSendResponse(ctx, version, keepAlive, response, isHead));
            }
        } else {
            ctx.executor().execute(() -> {
                var status = HttpResponseStatus.valueOf(response.code());
                var nettyResp = new DefaultHttpResponse(version, status);
                writeHeaders(nettyResp.headers(), response.headers(), body.contentType());

                if (body.contentLength() >= 0) {
                    nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.contentLength());
                } else {
                    nettyResp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }
                setupConnectionHeader(nettyResp, version, keepAlive);

                ctx.writeAndFlush(nettyResp);

                Thread.startVirtualThread(() -> {
                    AdaptiveNettyBodyOutputStream output;
                    try {
                        output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, null, body);
                        body.write(output);
                        output.complete();
                    } catch (Throwable e) {
                        closeBody(body);
                        if (e instanceof IOException || e.getCause() instanceof IOException) {
                            ctx.close();
                        } else {
                            ctx.fireExceptionCaught(e);
                        }
                    }
                });
            });
        }
    }

    private void prepareAndSendResponse(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, io.koraframework.http.server.common.response.HttpServerResponse response, boolean isHead) {
        var body = response.body();
        HttpResponseStatus status = HttpResponseStatus.valueOf(response.code());

        if (body == null) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), null);
            nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            finalizeAndWrite(ctx, nettyResp, version, keepAlive, null);
            return;
        }

        var declaredLength = body.contentLength();
        var contentType = body.contentType();

        if (isHead) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), contentType);
            if (declaredLength >= 0) {
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, declaredLength);
            }
            finalizeAndWrite(ctx, nettyResp, version, keepAlive, body);
            return;
        }

        AdaptiveNettyBodyOutputStream output = null;
        try {
            var content = body.getFullContentIfAvailable();
            if (content != null) {
                ByteBuf nettyBody = Unpooled.wrappedBuffer(content);
                var nettyResp = new DefaultFullHttpResponse(version, status, nettyBody);
                writeHeaders(nettyResp.headers(), response.headers(), contentType);
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, nettyBody.readableBytes());
                finalizeAndWrite(ctx, nettyResp, version, keepAlive, body);
                return;
            }

            var nettyResp = new DefaultHttpResponse(version, status);
            writeHeaders(nettyResp.headers(), response.headers(), contentType);
            if (declaredLength >= 0) {
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, declaredLength);
            } else {
                nettyResp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
            setupConnectionHeader(nettyResp, version, keepAlive);

            output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, null, body);
            body.write(output);
            output.complete();

        } catch (Throwable e) {
            closeBody(body);
            if ((output != null && output.isStreamMode()) || e instanceof IOException || e.getCause() instanceof IOException) {
                ctx.close();
            } else {
                ctx.fireExceptionCaught(e);
            }
        }
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, HttpMessage msg, HttpVersion version, boolean keepAlive, @Nullable HttpBodyOutput body) {
        setupConnectionHeader(msg, version, keepAlive);

        ctx.writeAndFlush(msg).addListener(future -> {
            closeBody(body);
            if (future.isSuccess()) {
                if (!keepAlive) {
                    ctx.close();
                }
            } else {
                ctx.close();
            }
        });
    }

    private void writeHeaders(HttpHeaders nettyHeaders, io.koraframework.http.common.header.HttpHeaders koraHeaders, @Nullable String contentType) {
        if (this.httpServerConfig.headerServerNameEnabled()) {
            nettyHeaders.set(HttpHeaderNames.SERVER, HEADER_SERVER_VALUE);
        }
        for (var header : koraHeaders) {
            var key = header.getKey();
            if (isReservedHeader(key, contentType)) {
                continue;
            }
            nettyHeaders.add(key, header.getValue());
        }
        if (contentType != null) {
            nettyHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
    }

    private static boolean isReservedHeader(String key, @Nullable String contentType) {
        if (HttpHeaderNames.SERVER.contentEqualsIgnoreCase(key)
            || HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(key)
            || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(key)) {
            return true;
        }
        return HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(key) && contentType != null;
    }

    private void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
        boolean keepAliveAllowed = keepAlive && this.httpServerConfig.headerKeepAliveEnabled();
        if (keepAliveAllowed) {
            if (!version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        } else {
            if (version.isKeepAliveDefault()) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
        }
    }

    private static void closeBody(@Nullable HttpBodyOutput body) {
        if (body != null) {
            try {
                body.close();
            } catch (IOException e) {
                log.error("Failed to close response body", e);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty pipeline exception caught", cause);
        ctx.close();
    }
}
