package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.*;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.router.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.util.*;

public class NettyUnroutedHttpRequest implements UnroutedHttpRequest {

    protected final FullHttpRequest nettyRequest;
    private final String method;
    private final String path;
    private final NettyHttpHeaders headers;
    private final long startTime;
    private final Map<String, List<String>> queryParams;
    @Nullable
    private final HttpBodyInput body;

    public NettyUnroutedHttpRequest(FullHttpRequest nettyRequest, @Nullable NettyRequestHttpBody streamingBody) {
        this.nettyRequest = nettyRequest;
        this.method = nettyRequest.method().name();
        this.startTime = System.nanoTime();

        var decoder = new QueryStringDecoder(nettyRequest.uri());
        this.path = decoder.path();
        this.queryParams = Collections.unmodifiableMap(decoder.parameters());
        this.headers = new NettyHttpHeaders(nettyRequest.headers());
        this.body = streamingBody != null ? streamingBody : (nettyRequest.content().readableBytes() == 0 ? HttpBody.empty() : createFallbackBody(nettyRequest));
    }

    private static NettyRequestHttpBody createFallbackBody(FullHttpRequest nettyRequest) {
        var nettyBody = new NettyRequestHttpBody(nettyRequest, null, 10 * 1024 * 1024);
        nettyBody.prepare();
        return nettyBody;
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
        var localBody = this.body;
        if (localBody != null) {
            return localBody;
        }
        return HttpBody.empty();
    }

    @Override
    public long requestStartTimeInNanos() {
        return this.startTime;
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
