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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class NettyUnroutedHttpRequest implements UnroutedHttpRequest {

    private static final String DEFAULT_HOST = "localhost";
    private static final String SCHEME_HTTP = "http";
    private static final String HEADER_X_FORWARDED_PROTO = "X-Forwarded-Proto";

    private static final AtomicReferenceFieldUpdater<NettyUnroutedHttpRequest, HttpBodyInput> BODY_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(NettyUnroutedHttpRequest.class, HttpBodyInput.class, "body");

    protected final FullHttpRequest nettyRequest;
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
    public final String method() {
        return this.method;
    }

    @Override
    public final String path() {
        return this.path;
    }

    @Override
    public final String host() {
        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        return host != null ? host : DEFAULT_HOST;
    }

    @Override
    public final String scheme() {
        String forwardedProto = nettyRequest.headers().get(HEADER_X_FORWARDED_PROTO);
        return forwardedProto != null ? forwardedProto.toLowerCase(Locale.ROOT) : SCHEME_HTTP;
    }

    @Override
    public final HttpHeaders headers() {
        return this.headers;
    }

    @Override
    public final Map<String, List<String>> queryParams() {
        return this.queryParams;
    }

    @Override
    public final HttpBodyInput body() {
        var body = this.body;
        if (body != null) {
            return body;
        }
        try {
            body = this.getContent();
            if (BODY_UPDATER.compareAndSet(this, null, body)) {
                if (body instanceof NettyRequestHttpBody nettyBody) {
                    nettyBody.prepare();
                }
                return body;
            }
            return this.body;
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP request body cannot be opened for " + this.method + " " + this.path, e);
        }
    }

    @Override
    public final long requestStartTimeInNanos() {
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
        nettyParams.forEach((key, values) -> {
            if (values.isEmpty()) {
                queryParams.put(key, Collections.emptyList());
            } else {
                queryParams.put(key, Collections.unmodifiableList(values));
            }
        });

        return Collections.unmodifiableMap(queryParams);
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
