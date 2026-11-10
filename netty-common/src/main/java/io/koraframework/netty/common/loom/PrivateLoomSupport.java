package io.koraframework.netty.common.loom;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

public final class PrivateLoomSupport {
    private static final MethodHandle DEFAULT_SCHEDULER;
    private static final MethodHandle BUILDER_SCHEDULER;
    private static final MethodHandle CARRIER_THREAD;
    private static final MethodHandle THREAD_SCHEDULER;
    private static final Throwable FAILURE;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle defaultScheduler;
        MethodHandle builderScheduler;
        MethodHandle threadScheduler;
        Throwable failure;

        try {
            Field defaultSchedulerField = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("DEFAULT_SCHEDULER");
            defaultSchedulerField.setAccessible(true);
            defaultScheduler = lookup.unreflectGetter(defaultSchedulerField);

            Field schedulerField = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder")
                    .getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            builderScheduler = lookup.unreflectSetter(schedulerField)
                    .asType(MethodType.methodType(void.class, Object.class, Executor.class));

            Field threadSchedulerField = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("scheduler");
            threadSchedulerField.setAccessible(true);
            threadScheduler = lookup.unreflectGetter(threadSchedulerField)
                    .asType(MethodType.methodType(Executor.class, Thread.class));

            failure = null;
        } catch (ReflectiveOperationException | InaccessibleObjectException roe) {
            defaultScheduler = null;
            builderScheduler = null;
            threadScheduler = null;
            failure = roe;
        }
        DEFAULT_SCHEDULER = defaultScheduler;
        BUILDER_SCHEDULER = builderScheduler;
        THREAD_SCHEDULER = threadScheduler;
        FAILURE = failure;

        MethodHandle carrierThread;
        try {
            Field carrierThreadField = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("carrierThread");
            carrierThreadField.setAccessible(true);
            carrierThread = lookup.unreflectGetter(carrierThreadField)
                    .asType(MethodType.methodType(Thread.class, Thread.class));
        } catch (Exception e) {
            carrierThread = null;
        }
        CARRIER_THREAD = carrierThread;
    }

    public static ForkJoinPool getDefaultScheduler() {
        try {
            return (ForkJoinPool) DEFAULT_SCHEDULER.invokeExact();
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        }
    }

    public static void setScheduler(Object builder, Executor executor) {
        try {
            BUILDER_SCHEDULER.invokeExact(builder, executor);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        }
    }

    public static Thread getCarrierThread(Thread t) {
        try {
            return (Thread) CARRIER_THREAD.invokeExact(t);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        }
    }

    public static Executor getScheduler(Thread t) {
        try {
            return (Executor) THREAD_SCHEDULER.invokeExact(t);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported() {
        return THREAD_SCHEDULER != null;
    }

    public static boolean isCarrierThreadSupported() {
        return CARRIER_THREAD != null;
    }

    public static boolean isCarrierThreadSupportedCheck() {
        if (!isSupported() && !LoomBranchSupport.isSupported()) {
            throw new IllegalStateException("Failed to access loom internals. Please make sure to add the `--add-opens=java.base/java.lang=ALL-UNNAMED` JVM argument. (" + FAILURE + ")");
        }
        return true;
    }
}
