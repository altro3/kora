package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBodyInput;
import io.koraframework.http.common.cookie.Cookie;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.request.HttpServerRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private List<Cookie> cookies;

    public NettyHttpServerRequest(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
        this.startTime = System.nanoTime();

        QueryStringDecoder decoder = new QueryStringDecoder(nettyRequest.uri());
        this.path = decoder.path();
        this.queryParams = decoder.parameters();
        this.headers = new NettyHttpHeaders(nettyRequest.headers());
        this.body = new NettyRequestHttpBody(nettyRequest);
    }

    @Override
    public String host() {
        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        return host != null ? host : DEFAULT_HOST;
    }

    @Override
    public String scheme() {
        String forwardedProto = nettyRequest.headers().get(HEADER_X_FORWARDED_PROTO);
        return forwardedProto != null ? forwardedProto.toLowerCase() : SCHEME_HTTP;
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
        if (this.cookies == null) {
            String cookieHeader = nettyRequest.headers().get(HttpHeaderNames.COOKIE);
            if (cookieHeader == null) {
                this.cookies = Collections.emptyList();
            } else {
                this.cookies = ServerCookieDecoder.STRICT.decode(cookieHeader).stream()
                    .map(nettyCookie -> Cookie.of(nettyCookie.name(), nettyCookie.value()))
                    .collect(Collectors.toList());
            }
        }
        return this.cookies;
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
