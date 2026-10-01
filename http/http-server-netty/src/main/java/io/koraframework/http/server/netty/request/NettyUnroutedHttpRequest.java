package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.HttpBody;
import io.koraframework.http.common.body.HttpBodyInput;
import io.koraframework.http.common.header.HttpHeaders;
import io.koraframework.http.server.common.router.UnroutedHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NettyUnroutedHttpRequest implements UnroutedHttpRequest {

    private static final String DEFAULT_HOST = "localhost";
    private static final String SCHEME_HTTP = "http";
    private static final String HEADER_X_FORWARDED_PROTO = "X-Forwarded-Proto";

    protected final FullHttpRequest nettyRequest;
    private final String method;
    private final String path;
    private final NettyHttpHeaders headers;
    private final long startTime;
    private final Map<String, List<String>> queryParams;
    @Nullable
    private volatile HttpBodyInput body;

    public NettyUnroutedHttpRequest(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
        this.method = nettyRequest.method().name();
        this.startTime = System.nanoTime();

        QueryStringDecoder decoder = new QueryStringDecoder(nettyRequest.uri());
        this.path = decoder.path();
        this.queryParams = Collections.unmodifiableMap(decoder.parameters());
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
        return host != null ? host : DEFAULT_HOST;
    }

    @Override
    public String scheme() {
        String forwardedProto = nettyRequest.headers().get(HEADER_X_FORWARDED_PROTO);
        return forwardedProto != null ? forwardedProto.toLowerCase(Locale.ROOT) : SCHEME_HTTP;
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

        try {
            var newBody = this.getContent();
            if (this.body == null) {
                this.body = newBody;
                return newBody;
            }
            if (newBody instanceof NettyRequestHttpBody nettyBody) {
                nettyBody.close();
            }
            var winnerBody = this.body;
            return winnerBody != null ? winnerBody : newBody;
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP request body cannot be opened for " + this.method + " " + this.path, e);
        }
    }

    @Override
    public long requestStartTimeInNanos() {
        return this.startTime;
    }

    private HttpBodyInput getContent() throws IOException {
        if (nettyRequest.content().readableBytes() == 0) {
            return HttpBody.empty();
        }
        var nettyBody = new NettyRequestHttpBody(nettyRequest);
        nettyBody.prepare();
        return nettyBody;
    }

    @Override
    public String toString() {
        return nettyRequest.toString();
    }
}
