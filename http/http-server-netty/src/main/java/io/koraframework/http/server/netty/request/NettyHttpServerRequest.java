package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.cookie.Cookie;
import io.koraframework.http.server.common.request.HttpServerRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.stream.Collectors;

public final class NettyHttpServerRequest extends NettyUnroutedHttpRequest implements HttpServerRequest {

    private static final AtomicReferenceFieldUpdater<NettyHttpServerRequest, List> COOKIES_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(NettyHttpServerRequest.class, List.class, "cookies");

    private final Map<String, String> pathParams;
    @Nullable
    private final String pathTemplate;
    private volatile List<Cookie> cookies;

    public NettyHttpServerRequest(FullHttpRequest nettyRequest, @Nullable String pathTemplate, Map<String, String> pathParams) {
        super(nettyRequest);
        this.pathTemplate = pathTemplate;
        this.pathParams = pathParams;
    }

    @Override
    @Nullable
    public String pathTemplate() {
        return this.pathTemplate;
    }

    @Override
    public Map<String, String> pathParams() {
        return this.pathParams;
    }

    @Override
    public List<Cookie> cookies() {
        var c = this.cookies;
        if (c != null) {
            return c;
        }
        String cookieHeader = this.nettyRequest.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader == null) {
            c = Collections.emptyList();
        } else {
            c = ServerCookieDecoder.STRICT.decode(cookieHeader).stream()
                .map(nettyCookie -> Cookie.of(nettyCookie.name(), nettyCookie.value()))
                .collect(Collectors.toList());
        }
        if (COOKIES_UPDATER.compareAndSet(this, null, c)) {
            return c;
        }
        return this.cookies;
    }
}
