package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.koraframework.http.common.cookie.Cookie;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.request.HttpServerRequest;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NettyHttpServerRequest implements HttpServerRequest {

    private static final String DEFAULT_HOST = "localhost";
    private static final String SCHEME_HTTP = "http";
    private static final String HEADER_X_FORWARDED_PROTO = "X-Forwarded-Proto";

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

        var headersByName = new LinkedHashMap<String, List<String>>(nettyRequest.headers().size());
        for (String name : nettyRequest.headers().names()) {
            headersByName.put(name, nettyRequest.headers().getAll(name));
        }
        this.headers = HttpHeaders.of(headersByName);

        String contentType = nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
        long contentLength = nettyRequest.content().readableBytes();

        this.body = new NettyHttpBodyInput(nettyRequest, contentType, contentLength);
    }

    private static final class NettyHttpBodyInput implements HttpBodyInput {

        private final FullHttpRequest request;
        private final String contentType;
        private final long contentLength;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private NettyHttpBodyInput(FullHttpRequest request, String contentType, long contentLength) {
            this.request = request;
            this.contentType = contentType;
            this.contentLength = contentLength;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public InputStream asInputStream() {
            return new ByteBufInputStream(request.content().duplicate());
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                request.release();
            }
        }
    }

    @Override
    public String host() {
        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        return host != null ? host : DEFAULT_HOST;
    }

    @Override
    public String scheme() {
        String forwardedProto = nettyRequest.headers().get(HEADER_X_FORWARDED_PROTO);
        if (forwardedProto != null) {
            return forwardedProto.toLowerCase();
        }
        return SCHEME_HTTP;
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
