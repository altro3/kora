package io.koraframework.common.context;

import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class KoraContext {

    private static final ScopedValue<KoraContext> SCOPED_VALUE = ScopedValue.newInstance();

    private final Object[] values;

    private KoraContext(Object[] values) {
        this.values = values;
    }

    public static KoraContext empty() {
        return new KoraContext(new Object[0]);
    }

    public static @Nullable KoraContext current() {
        return SCOPED_VALUE.isBound() ? SCOPED_VALUE.get() : null;
    }

    public static <R> R runWith(KoraContext context, Supplier<R> action) {
        return ScopedValue.where(SCOPED_VALUE, context).call(action::get);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    public <R> R call(Callable<R> action) {
        return ScopedValue.where(SCOPED_VALUE, this).call(() -> {
            try {
                return action.call();
            } catch (Throwable e) {
                KoraContext.sneakyThrow(e);
                return null;
            }
        });
    }

    public void run(Runnable action) {
        ScopedValue.where(SCOPED_VALUE, this).run(action);
    }

    public <T> @Nullable T get(Key<T> key) {
        int index = key.index();
        if (index < values.length) {
            @SuppressWarnings("unchecked")
            var value = (T) values[index];
            return value;
        }
        return null;
    }

    public <T> KoraContext with(Key<T> key, T value) {
        Objects.requireNonNull(key);
        int index = key.index();
        var newValues = Arrays.copyOf(this.values, Math.max(this.values.length, index + 1));
        newValues[index] = value;
        return new KoraContext(newValues);
    }

    public static final class Key<T> {

        private static final AtomicInteger INDEX_COUNTER = new AtomicInteger(0);

        private final int index;

        private Key(int index) {
            this.index = index;
        }

        public static <T> Key<T> create() {
            return new Key<>(INDEX_COUNTER.getAndIncrement());
        }

        int index() {
            return index;
        }
    }
}
