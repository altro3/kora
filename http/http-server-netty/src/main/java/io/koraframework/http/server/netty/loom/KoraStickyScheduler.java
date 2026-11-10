package io.koraframework.http.server.netty.loom;

import io.netty.channel.*;

import java.util.concurrent.*;

public final class KoraStickyScheduler implements Executor {
    private final ChannelHandlerContext ctx;

    public KoraStickyScheduler(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void execute(Runnable command) {
        if (ctx.executor().inEventLoop()) {
            command.run();
        } else {
            ctx.executor().execute(command);
        }
    }
}
