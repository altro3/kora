package io.koraframework.netty.common.loom;

import io.netty.channel.*;
import io.netty.util.concurrent.*;

import java.util.concurrent.*;

public final class EventLoopGroupFactory {

    private final LoomCarrierConfig loomConfig;
    private final EventLoopLoomFactory eventLoopLoomFactory;

    public EventLoopGroupFactory(LoomCarrierConfig loomConfig, EventLoopLoomFactory eventLoopLoomFactory) {
        this.loomConfig = loomConfig;
        this.eventLoopLoomFactory = eventLoopLoomFactory;
    }

    public EventLoopGroup createEventLoopGroup(int nThreads, String namePrefix, boolean isLoomCarrier, IoHandlerFactory ioHandlerFactory) {
        var threadFactory = new DefaultThreadFactory(namePrefix, true);
        Executor executor = command -> threadFactory.newThread(command).start();

        if (isLoomCarrier) {
            LoomCarrierGroup.Factory carrierFactory = new LoomCarrierGroup.Factory(
                eventLoopLoomFactory,
                loomConfig
            );
            return carrierFactory.create(nThreads, executor, ioHandlerFactory);
        } else {
            return new MultiThreadIoEventLoopGroup(nThreads, executor, ioHandlerFactory);
        }
    }
}
