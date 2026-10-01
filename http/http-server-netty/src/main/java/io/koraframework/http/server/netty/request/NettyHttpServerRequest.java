package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.koraframework.http.common.cookie.Cookie;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.request.HttpServerRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class NettyHttpServerRequest implements HttpServerRequest {
    private final FullHttpRequest nettyRequest;
    private final String path;
    private final HttpHeaders headers;
    private final HttpBodyInput body;
    private final Map<String, List<String>> queryParams;
    private final long startTime;

    public NettyHttpServerRequest(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
        this.startTime = System.nanoTime();

        QueryStringDecoder decoder = new QueryStringDecoder(nettyRequest.uri());
        this.path = decoder.path();
        this.queryParams = decoder.parameters();

        var koraHeaders = HttpHeaders.of();
        for (Map.Entry<String, String> entry : nettyRequest.headers()) {
            koraHeaders = koraHeaders.add(entry.getKey(), entry.getValue());
        }
        this.headers = koraHeaders;

        byte[] bodyBytes;
        if (nettyRequest.content().isReadable()) {
            bodyBytes = new byte[nettyRequest.content().readableBytes()];
            nettyRequest.content().readBytes(bodyBytes);
        } else {
            bodyBytes = new byte[0];
        }

        String contentType = nettyRequest.headers().get("Content-Type");
        this.body = new HttpBodyInput() {
            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public long contentLength() {
                return bodyBytes.length;
            }

            @Override
            public InputStream asInputStream() {
                return new ByteArrayInputStream(bodyBytes);
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public String host() {
        String host = nettyRequest.headers().get("Host");
        return host != null ? host : "localhost";
    }

    @Override
    public String scheme() {
        String forwardedProto = nettyRequest.headers().get("X-Forwarded-Proto");
        if (forwardedProto != null) {
            return forwardedProto.toLowerCase();
        }
        return "http";
    }

    @Override
    public String method() {
        return nettyRequest.method().name();
    }

    @Override
    public String path() {
        return this.path;
    }

    @Override
    @Nullable
    public String pathTemplate() {
        return null;
    }

    @Override
    public Map<String, String> pathParams() {
        return Collections.emptyMap();
    }

    @Override
    public HttpHeaders headers() {
        return this.headers;
    }

    @Override
    public List<Cookie> cookies() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return this.queryParams;
    }

    @Override
    public HttpBodyInput body() {
        return this.body;
    }

    @Override
    public long requestStartTimeInNanos() {
        return this.startTime;
    }
}
