package io.koraframework.http.server.netty;

import io.netty.channel.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AsciiString;
import java.util.Map;

@ChannelHandler.Sharable
public final class KoraHeaderInternerHandler extends ChannelInboundHandlerAdapter {
    public static final KoraHeaderInternerHandler INSTANCE = new KoraHeaderInternerHandler();

    private static final Map<CharSequence, AsciiString> VALUE_CACHE = Map.of(
        HttpHeaderValues.APPLICATION_JSON, HttpHeaderValues.APPLICATION_JSON,
        HttpHeaderValues.TEXT_PLAIN, HttpHeaderValues.TEXT_PLAIN,
        AsciiString.cached("fasthttp"), AsciiString.cached("fasthttp"),
        AsciiString.cached("127.0.0.1:8090"), AsciiString.cached("127.0.0.1:8090"),
        HttpHeaderValues.CLOSE, HttpHeaderValues.CLOSE,
        HttpHeaderValues.KEEP_ALIVE, HttpHeaderValues.KEEP_ALIVE
    );

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest req) {
            var headers = req.headers();
            optimizeHeader(headers, HttpHeaderNames.CONTENT_TYPE);
            optimizeHeader(headers, HttpHeaderNames.ACCEPT);
            optimizeHeader(headers, HttpHeaderNames.USER_AGENT);
            optimizeHeader(headers, HttpHeaderNames.HOST);
            optimizeHeader(headers, HttpHeaderNames.CONNECTION);
        }
        ctx.fireChannelRead(msg);
    }

    private void optimizeHeader(io.netty.handler.codec.http.HttpHeaders headers, CharSequence name) {
        String rawValue = headers.get(name);
        if (rawValue != null) {
            for (Map.Entry<CharSequence, AsciiString> entry : VALUE_CACHE.entrySet()) {
                if (entry.getKey().toString().equalsIgnoreCase(rawValue)) {
                    headers.set(name, entry.getValue());
                    return;
                }
            }
        }
    }
}
