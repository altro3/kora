package io.koraframework.http.server.netty;

import io.koraframework.common.context.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

public record NettyContext(
    HttpRequest request,
    ChannelHandlerContext channelContext
) {

    public static final KoraContext.Key<NettyContext> NETTY_KEY = KoraContext.Key.create();

    @Nullable
    public static NettyContext get() {
        KoraContext current = KoraContext.current();
        if (current != null) {
            return current.get(NETTY_KEY);
        }
        return null;
    }

    @Nullable
    public static HttpRequest getRequest() {
        KoraContext current = KoraContext.current();
        if (current != null) {
            NettyContext nettyCtx = current.get(NETTY_KEY);
            if (nettyCtx != null) {
                return nettyCtx.request;
            }
        }
        return null;
    }

    @Nullable
    public static ChannelHandlerContext getChannelContext() {
        KoraContext current = KoraContext.current();
        if (current != null) {
            NettyContext nettyCtx = current.get(NETTY_KEY);
            if (nettyCtx != null) {
                return nettyCtx.channelContext;
            }
        }
        return null;
    }
}
