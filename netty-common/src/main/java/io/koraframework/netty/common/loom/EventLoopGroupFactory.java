package io.koraframework.netty.common.loom;

import io.netty.channel.*;
import io.netty.util.concurrent.*;
import org.slf4j.*;

import java.util.concurrent.*;

public final class EventLoopGroupFactory {

    private static final Logger log = LoggerFactory.getLogger(EventLoopGroupFactory.class);

    private final LoomCarrierConfig loomConfig;
    private final EventLoopLoomFactory eventLoopLoomFactory;

    public EventLoopGroupFactory(LoomCarrierConfig loomConfig, EventLoopLoomFactory eventLoopLoomFactory) {
        this.loomConfig = loomConfig;
        this.eventLoopLoomFactory = eventLoopLoomFactory;
    }

    public EventLoopGroup createEventLoopGroup(int nThreads, String namePrefix, boolean isLoomCarrier, IoHandlerFactory ioHandlerFactory) {
        var threadFactory = Thread.ofPlatform()
            .name(namePrefix + "-", 0)
            .daemon(true)
            .stackSize(256L * 1024L)
            .factory();
        Executor executor = command -> threadFactory.newThread(command).start();

        if (isLoomCarrier) {
            if (!PrivateLoomSupport.isSupported() && !LoomBranchSupport.isSupported()) {
                log.warn("Loom carrier internals are not accessible (missing --add-opens=java.base/java.lang=ALL-UNNAMED). Falling back to standard MultiThreadIoEventLoopGroup.");
                return new MultiThreadIoEventLoopGroup(nThreads, executor, ioHandlerFactory);
            }
            var carrierFactory = new LoomCarrierGroup.Factory(eventLoopLoomFactory, loomConfig);
            return carrierFactory.create(nThreads, executor, ioHandlerFactory);
        } else {
            return new MultiThreadIoEventLoopGroup(nThreads, executor, ioHandlerFactory);
        }
    }
}
