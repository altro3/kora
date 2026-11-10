package io.koraframework.http.server.netty.loom;

import io.netty.channel.*;
import io.netty.channel.nio.*;

import java.util.concurrent.*;

public final class KoraEventLoopGroupFactory {

    private final LoomCarrierConfig loomConfig;
    private final EventLoopLoomFactory eventLoopLoomFactory;

    public KoraEventLoopGroupFactory(LoomCarrierConfig loomConfig, EventLoopLoomFactory eventLoopLoomFactory) {
        this.loomConfig = loomConfig;
        this.eventLoopLoomFactory = eventLoopLoomFactory;
    }

    public EventLoopGroup createEventLoopGroup(int nThreads, String namePrefix, boolean isLoomCarrier) {
        Executor executor = command -> new Thread(command).start();

        IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory();

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

    public ThreadFactory createVirtualThreadFactory(String namePrefix, boolean isLoomCarrier) {
        return eventLoopLoomFactory.createVirtualThreadFactory(
            namePrefix,
            true,
            isLoomCarrier
        );
    }
}
