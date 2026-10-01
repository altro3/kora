package io.koraframework.http.server.netty;

import io.netty.handler.codec.http.FullHttpRequest;
import org.jspecify.annotations.Nullable;

public record NettyContext(FullHttpRequest request) {

    public static final ScopedValue<NettyContext> VALUE = ScopedValue.newInstance();

    @Nullable
    public static NettyContext get() {
        if (VALUE.isBound()) {
            return VALUE.get();
        } else {
            return null;
        }
    }

    @Nullable
    public static FullHttpRequest getRequest() {
        if (VALUE.isBound()) {
            return VALUE.get().request;
        } else {
            return null;
        }
    }
}
