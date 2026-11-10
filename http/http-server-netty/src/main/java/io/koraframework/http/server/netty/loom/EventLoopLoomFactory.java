package io.koraframework.http.server.netty.loom;

import io.netty.util.concurrent.*;

import java.util.concurrent.*;

public final class EventLoopLoomFactory {

    public final FastThreadLocal<ThreadFactory> targetScheduler = new FastThreadLocal<>();
    private final boolean isLoomCarrierEnabled;

    public EventLoopLoomFactory(boolean isLoomCarrierEnabled) {
        this.isLoomCarrierEnabled = isLoomCarrierEnabled;
    }

    public ThreadFactory createVirtualThreadFactory(String namePrefix, boolean isVirtualExecutor, boolean anyLoopIsLoomCarrier) {
        if (!isVirtualExecutor) {
            throw new IllegalStateException("Virtual executor should be virtual");
        }

        ThreadFactory delegate = Thread.ofVirtual().name(namePrefix, 1L).factory();
        if (!anyLoopIsLoomCarrier) {
            // no event loop will ever carry virtual threads, so there is no scheduler to pick
            return delegate;
        }

        return r -> {
            // getIfExists avoids allocating a thread local map for every thread that creates a
            // virtual thread and is not an event loop
            ThreadFactory currentTarget = targetScheduler.getIfExists();
            if (currentTarget == null) {
                return delegate.newThread(r);
            } else {
                return currentTarget.newThread(r);
            }
        };
    }
}
