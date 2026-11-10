package io.koraframework.http.server.netty.loom;

import io.netty.util.internal.*;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

public final class KoraLoomSupport {
    private static final MethodHandle BUILDER_SCHEDULER;
    private static final boolean SUPPORTED;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle builderScheduler = null;
        boolean supported;

        try {
            Field schedulerField = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder")
                .getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            builderScheduler = lookup.unreflectSetter(schedulerField)
                .asType(MethodType.methodType(void.class, Object.class, Executor.class));

            supported = true;
        } catch (Throwable e) {
            supported = false;
        }

        BUILDER_SCHEDULER = builderScheduler;
        SUPPORTED = supported;
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    public static void setScheduler(Thread.Builder.OfVirtual builder, Executor executor) {
        if (!SUPPORTED) {
            return;
        }
        try {
            BUILDER_SCHEDULER.invokeExact((Object) builder, executor);
        } catch (Throwable e) {
            PlatformDependent.throwException(e);
        }
    }
}
