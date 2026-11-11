package io.koraframework.http.server.netty.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.jspecify.annotations.Nullable;

public interface RequestHandler {
    
    void accept(ChannelHandlerContext ctx, HttpRequest request, @Nullable AutoCloseable body, KoraOutboundAccess outboundAccess);
    
    void handleUnboundError(Throwable cause);

    default void responseWritten(@Nullable Object attachment) {}

    default void responseFailed(@Nullable Object attachment, Throwable cause) {}

    default void removed() {}
}
