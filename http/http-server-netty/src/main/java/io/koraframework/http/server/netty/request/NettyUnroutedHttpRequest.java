package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBody;
import io.koraframework.http.common.body.HttpBodyInput;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.router.UnroutedHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public final class NettyUnroutedHttpRequest implements UnroutedHttpRequest {

    private final FullHttpRequest nettyRequest;
    private final String method;
    private final String path;
    private final NettyHttpHeaders headers;
    private final long startTime;
    private final Map<String, List<String>> queryParams;
    private volatile HttpBodyInput body;

    public NettyUnroutedHttpRequest(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
        this.method = nettyRequest.method().name();
        this.startTime = System.nanoTime();

        QueryStringDecoder decoder = new QueryStringDecoder(nettyRequest.uri());
        this.path = decoder.path();
        this.queryParams = queryParams(decoder);
        this.headers = new NettyHttpHeaders(nettyRequest.headers());
    }

    @Override
    public String method() {
        return this.method;
    }

    @Override
    public String path() {
        return this.path;
    }

    @Override
    public String host() {
        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        return host != null ? host : "localhost";
    }

    @Override
    public String scheme() {
        String forwardedProto = nettyRequest.headers().get("X-Forwarded-Proto");
        return forwardedProto != null ? forwardedProto.toLowerCase(Locale.ROOT) : "http";
    }

    @Override
    public HttpHeaders headers() {
        return this.headers;
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return this.queryParams;
    }

    @Override
    public HttpBodyInput body() {
        var b = this.body;
        if (b != null) {
            return b;
        }
        try {
            b = this.getContent();
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP request body cannot be opened for %s %s; cause: %s".formatted(this.method, this.path, e.getMessage()), e);
        }
        return this.body = b;
    }

    @Override
    public long requestStartTimeInNanos() {
        return this.startTime;
    }

    private HttpBodyInput getContent() throws IOException {
        if (nettyRequest.content().readableBytes() == 0) {
            return HttpBody.empty();
        }
        return new NettyRequestHttpBody(nettyRequest);
    }

    private static Map<String, List<String>> queryParams(QueryStringDecoder decoder) {
        var nettyParams = decoder.parameters();
        if (nettyParams.isEmpty()) {
            return Map.of();
        }
        var queryParams = new LinkedHashMap<String, List<String>>(nettyParams.size());
        for (var entry : nettyParams.entrySet()) {
            var key = entry.getKey();
            var value = new ArrayList<String>(entry.getValue().size());
            for (var it : entry.getValue()) {
                if (!it.isEmpty()) {
                    value.add(it);
                }
            }
            queryParams.put(key, Collections.unmodifiableList(value));
        }
        return Collections.unmodifiableMap(queryParams);
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
