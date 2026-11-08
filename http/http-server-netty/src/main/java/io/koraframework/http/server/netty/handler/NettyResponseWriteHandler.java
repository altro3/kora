package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.telemetry.*;
import io.koraframework.http.server.netty.handler.NettyTelemetryHandler.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.io.*;

import static io.koraframework.http.server.netty.handler.NettyTelemetryHandler.OBSERVATION_KEY;

@ChannelHandler.Sharable
public final class NettyResponseWriteHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(NettyResponseWriteHandler.class);
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

    private final ValueOf<HttpServerConfig> httpServerConfig;

    public NettyResponseWriteHandler(ValueOf<HttpServerConfig> httpServerConfig) {
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
        var observation = container.observation();
        boolean isHead = nettyReq.method() == HttpMethod.HEAD;

        var body = response.body();
        var content = body != null ? body.getFullContentIfAvailable() : null;

        if (body == null || isHead || content != null) {
            if (ctx.executor().inEventLoop()) {
                prepareAndSendResponse(ctx, nettyReq, version, keepAlive, response, isHead, observation);
            } else {
                ctx.executor().execute(() -> prepareAndSendResponse(ctx, nettyReq, version, keepAlive, response, isHead, observation));
            }
            return;
        }

        ctx.executor().execute(() -> {
            Thread.startVirtualThread(() -> {
                var status = HttpResponseStatus.valueOf(response.code());
                var nettyResp = new DefaultHttpResponse(version, status);
                writeHeaders(nettyResp.headers(), response.headers(), body.contentType());

                if (body.contentLength() >= 0) {
                    nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.contentLength());
                } else {
                    nettyResp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }
                setupConnectionHeader(nettyResp, version, keepAlive);

                AdaptiveNettyBodyOutputStream output = null;
                try {
                    output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, body, nettyReq, observation);
                    output.writeBody();
                    output.complete();

                    if (nettyReq != null) {
                        io.netty.util.ReferenceCountUtil.safeRelease(nettyReq);
                    }

                    var dispatcher = ctx.pipeline().get(KoraVirtualThreadDispatcher.class);
                    if (dispatcher != null) {
                        dispatcher.getActiveRequests().decrementAndGet();
                        dispatcher.getPhaser().arriveAndDeregister();
                    }

                } catch (Throwable e) {
                    if (output != null) {
                        output.close();
                    }
                    closeBody(body);
                    ctx.channel().config().setAutoRead(true);

                    if (output != null && !output.isStreamMode() && ctx.channel().isActive()) {
                        var message = e.getMessage() != null ? e.getMessage() : "Internal Server Error";
                        var bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        var buffer = ctx.alloc().directBuffer(bytes.length).writeBytes(bytes);

                        var errResp = new DefaultFullHttpResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR, buffer);
                        errResp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
                        errResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

                        setupConnectionHeader(errResp, version, keepAlive);
                        ctx.writeAndFlush(errResp).addListener(_ -> ctx.close());
                    } else {
                        ctx.close();
                    }
                }
            });
        });
    }

    private void prepareAndSendResponse(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpVersion version, boolean keepAlive, HttpServerResponse response, boolean isHead, HttpServerObservation observation) {
        var body = response.body();
        HttpResponseStatus status = HttpResponseStatus.valueOf(response.code());

        if (body == null) {
            var nettyResp = new DefaultFullHttpResponse(version, status, Unpooled.EMPTY_BUFFER);
            writeHeaders(nettyResp.headers(), response.headers(), null);
            nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, null, observation);
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
            finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, body, observation);
            return;
        }

        try {
            var content = body.getFullContentIfAvailable();
            if (content != null) {
                ByteBuf nettyBody = Unpooled.wrappedBuffer(content);
                var nettyResp = new DefaultFullHttpResponse(version, status, nettyBody);
                writeHeaders(nettyResp.headers(), response.headers(), contentType);
                nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, nettyBody.readableBytes());
                finalizeAndWrite(ctx, nettyReq, nettyResp, version, keepAlive, body, observation);
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

            var output = new AdaptiveNettyBodyOutputStream(ctx, nettyResp, keepAlive, body, nettyReq, observation);
            body.write(output);
            output.complete();

        } catch (Throwable e) {
            if (observation != null) {
                observation.observeError(e);
                observation.end();
            }
            closeBody(body);
            ReferenceCountUtil.safeRelease(nettyReq);
            ctx.close();
        }
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpMessage msg, HttpVersion version, boolean keepAlive, @Nullable HttpBodyOutput body, @Nullable HttpServerObservation observation) {
        var statusStr = (msg instanceof HttpResponse res) ? res.status().toString() : "unknown";

        setupConnectionHeader(msg, version, keepAlive);

        if (keepAlive) {
            ctx.channel().config().setAutoRead(true);
        }

        ctx.writeAndFlush(msg).addListener(future -> {
            closeBody(body);

            var dispatcher = ctx.pipeline().get(KoraVirtualThreadDispatcher.class);
            if (dispatcher != null) {
                dispatcher.getActiveRequests().decrementAndGet();
                dispatcher.getPhaser().arriveAndDeregister();
            }


            if (observation != null) {
                if (!future.isSuccess()) {
                    observation.observeError(future.cause());
                }
                observation.end();
            }

            if (nettyReq != null) {
                ReferenceCountUtil.safeRelease(nettyReq);
            }

            // Если запись провалилась или это не Keep-Alive, только тогда принудительно закрываем
            if (!future.isSuccess() || !keepAlive) {
                ctx.close();
            }
        });
    }

    private void writeHeaders(HttpHeaders nettyHeaders, io.koraframework.http.common.header.HttpHeaders koraHeaders, @Nullable String contentType) {
        if (this.httpServerConfig.get().headerServerNameEnabled()) {
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
        boolean keepAliveAllowed = keepAlive && this.httpServerConfig.get().headerKeepAliveEnabled();
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
