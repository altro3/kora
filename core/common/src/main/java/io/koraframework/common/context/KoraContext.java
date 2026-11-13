package io.koraframework.common.context;

import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class KoraContext {

    private static final ScopedValue<KoraContext> SCOPED_VALUE = ScopedValue.newInstance();
    private static final int DEFAULT_INITIAL_CAPACITY = 5;

    private Object[] values;

    private KoraContext(Object[] values) {
        this.values = values;
    }

    public static KoraContext empty() {
        int size = Math.max(DEFAULT_INITIAL_CAPACITY, Key.INDEX_COUNTER.get());
        return new KoraContext(new Object[size]);
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
                sneakyThrow(e);
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

    public <T> KoraContext with(Key<T> key, @Nullable T value) {
        Objects.requireNonNull(key);
        int index = key.index();
        if (index >= values.length) {
            values = Arrays.copyOf(values, Math.max(index + 1, values.length + 2));
        }
        values[index] = value;
        return this;
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
