package io.koraframework.http.server.netty.handler;

import io.koraframework.application.graph.*;
import io.koraframework.http.common.body.*;
import io.koraframework.http.server.common.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.http.server.common.telemetry.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.io.*;

import static io.netty.util.ReferenceCountUtil.*;

@ChannelHandler.Sharable
public final class NettyResponseWriteHandler extends ChannelOutboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(NettyResponseWriteHandler.class);
    private static final AsciiString HEADER_SERVER_VALUE = AsciiString.cached("Kora");

    private final ValueOf<HttpServerConfig> httpServerConfig;

    public NettyResponseWriteHandler(ValueOf<HttpServerConfig> httpServerConfig) {
        this.httpServerConfig = httpServerConfig;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof KoraVirtualThreadDispatcher.ResponseContainer container)) {
            ctx.write(msg, promise);
            return;
        }

        var nettyReq = container.nettyReq();
        var version = nettyReq.protocolVersion();
        var keepAlive = HttpUtil.isKeepAlive(nettyReq);
        var response = container.koraResponse();
        var observation = container.observation();
        boolean isHead = nettyReq.method() == HttpMethod.HEAD;

        prepareAndSendResponse(ctx, nettyReq, version, keepAlive, response, isHead, observation);
    }

    void prepareAndSendResponse(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpVersion version,
                                boolean keepAlive, HttpServerResponse response, boolean isHead, HttpServerObservation observation) {
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
            safeRelease(nettyReq);
            ctx.close();
        }
    }

    private void finalizeAndWrite(ChannelHandlerContext ctx, FullHttpRequest nettyReq, HttpMessage msg,
                                  HttpVersion version, boolean keepAlive, @Nullable HttpBodyOutput body,
                                  @Nullable HttpServerObservation observation) {
        setupConnectionHeader(msg, version, keepAlive);

        if (keepAlive) {
            ctx.channel().config().setAutoRead(true);
        }

        ctx.write(msg).addListener(new FinalizeWriteListener(ctx, body, observation, nettyReq, keepAlive));
    }

    void writeHeaders(HttpHeaders nettyHeaders, io.koraframework.http.common.header.HttpHeaders koraHeaders, @Nullable String contentType) {
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
        return HttpHeaderNames.SERVER.contentEqualsIgnoreCase(key)
            || HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(key)
            || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(key)
            || (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(key) && contentType != null);
    }

    void setupConnectionHeader(HttpMessage response, HttpVersion version, boolean keepAlive) {
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

    static void closeBody(@Nullable HttpBodyOutput body) {
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
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    private static final class FinalizeWriteListener implements ChannelFutureListener {
        private final ChannelHandlerContext ctx;
        private final HttpBodyOutput body;
        private final HttpServerObservation observation;
        private final FullHttpRequest nettyReq;
        private final boolean keepAlive;

        public FinalizeWriteListener(ChannelHandlerContext ctx, HttpBodyOutput body,
                                     HttpServerObservation observation, FullHttpRequest nettyReq, boolean keepAlive) {
            this.ctx = ctx;
            this.body = body;
            this.observation = observation;
            this.nettyReq = nettyReq;
            this.keepAlive = keepAlive;
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            closeBody(body);

            if (observation != null) {
                if (!future.isSuccess()) {
                    observation.observeError(future.cause());
                }
                observation.end();
            }

            if (nettyReq != null) {
                safeRelease(nettyReq);
            }

            if (future.isSuccess()) {
                if (keepAlive) {
                    ctx.read();
                } else {
                    ctx.close();
                }
            } else {
                ctx.close();
            }
        }
    }
}
