package io.koraframework.common.telemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

import java.util.concurrent.Callable;
import java.util.function.*;

public class OpentelemetryContext implements Context {
    public static final ScopedValue<Context> VALUE = ScopedValue.newInstance();

    private final Context delegate;

    public OpentelemetryContext(Context delegate) {
        this.delegate = delegate;
    }

    @Override
    public Scope makeCurrent() {
        throw new IllegalStateException();
    }

    @Override
    public <V> V get(ContextKey<V> key) {
        return delegate.get(key);
    }

    /**
     * The derived context has to stay a Kora one: this class carries the only {@link ScopedValue}-based
     * implementation of {@code wrap(...)}. A bare delegate falls back to the default {@code wrap(...)},
     * which calls {@code makeCurrent()} and therefore {@code ContextStorage.attach(...)} -- unsupported here.
     */
    @Override
    public <V> Context with(ContextKey<V> k1, V v1) {
        return new OpentelemetryContext(delegate.with(k1, v1));
    }

    @Override
    public Runnable wrap(Runnable runnable) {
        return new WrappedRunnable(this, runnable);
    }

    @Override
    public <T> Callable<T> wrap(Callable<T> callable) {
        return new WrappedCallable<>(this, callable);
    }

    @Override
    public <T, U> Function<T, U> wrapFunction(Function<T, U> function) {
        return new WrappedFunction<>(this, function);
    }

    @Override
    public <T, U, V> BiFunction<T, U, V> wrapFunction(BiFunction<T, U, V> function) {
        return new WrappedBiFunction<>(this, function);
    }

    @Override
    public <T> Consumer<T> wrapConsumer(Consumer<T> consumer) {
        return new WrappedConsumer<>(this, consumer);
    }

    @Override
    public <T, U> BiConsumer<T, U> wrapConsumer(BiConsumer<T, U> consumer) {
        return new WrappedBiConsumer<>(this, consumer);
    }

    @Override
    public <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        return new WrappedSupplier<>(this, supplier);
    }

    private static final class WrappedRunnable implements Runnable {
        private final OpentelemetryContext ctx;
        private final Runnable delegate;

        WrappedRunnable(OpentelemetryContext ctx, Runnable delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            ScopedValue.where(VALUE, ctx).run(delegate);
        }
    }

    private static final class WrappedCallable<T> implements Callable<T> {
        private final OpentelemetryContext ctx;
        private final Callable<T> delegate;

        WrappedCallable(OpentelemetryContext ctx, Callable<T> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public T call() throws Exception {
            // Оборачиваем в CallableOp, так как оригинальный delegate может кидать Exception
            return ScopedValue.where(VALUE, ctx).call(new CallableRunner<>(delegate));
        }

        private static final class CallableRunner<T> implements ScopedValue.CallableOp<T, RuntimeException> {
            private final Callable<T> callable;

            CallableRunner(Callable<T> callable) {
                this.callable = callable;
            }

            @Override
            public T call() {
                try {
                    return callable.call();
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static final class WrappedFunction<T, U> implements Function<T, U> {
        private final OpentelemetryContext ctx;
        private final Function<T, U> delegate;

        WrappedFunction(OpentelemetryContext ctx, Function<T, U> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public U apply(T t) {
            return ScopedValue.where(VALUE, ctx).call(new FunctionRunner<>(delegate, t));
        }

        private static final class FunctionRunner<T, U> implements ScopedValue.CallableOp<U, RuntimeException> {
            private final Function<T, U> function;
            private final T arg;

            FunctionRunner(Function<T, U> function, T arg) {
                this.function = function;
                this.arg = arg;
            }

            @Override
            public U call() {
                return function.apply(arg);
            }
        }
    }

    private static final class WrappedBiFunction<T, U, V> implements BiFunction<T, U, V> {
        private final OpentelemetryContext ctx;
        private final BiFunction<T, U, V> delegate;

        WrappedBiFunction(OpentelemetryContext ctx, BiFunction<T, U, V> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public V apply(T t, U u) {
            return ScopedValue.where(VALUE, ctx).call(new BiFunctionRunner<>(delegate, t, u));
        }

        private static final class BiFunctionRunner<T, U, V> implements ScopedValue.CallableOp<V, RuntimeException> {
            private final BiFunction<T, U, V> function;
            private final T arg1;
            private final U arg2;

            BiFunctionRunner(BiFunction<T, U, V> function, T arg1, U arg2) {
                this.function = function;
                this.arg1 = arg1;
                this.arg2 = arg2;
            }

            @Override
            public V call() {
                return function.apply(arg1, arg2);
            }
        }
    }

    private static final class WrappedConsumer<T> implements Consumer<T> {
        private final OpentelemetryContext ctx;
        private final Consumer<T> delegate;

        WrappedConsumer(OpentelemetryContext ctx, Consumer<T> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public void accept(T t) {
            ScopedValue.where(VALUE, ctx).run(new ConsumerRunner<>(delegate, t));
        }

        private static final class ConsumerRunner<T> implements Runnable {
            private final Consumer<T> consumer;
            private final T arg;

            ConsumerRunner(Consumer<T> consumer, T arg) {
                this.consumer = consumer;
                this.arg = arg;
            }

            @Override
            public void run() {
                consumer.accept(arg);
            }
        }
    }

    private static final class WrappedBiConsumer<T, U> implements BiConsumer<T, U> {
        private final OpentelemetryContext ctx;
        private final BiConsumer<T, U> delegate;

        WrappedBiConsumer(OpentelemetryContext ctx, BiConsumer<T, U> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public void accept(T t, U u) {
            ScopedValue.where(VALUE, ctx).run(new BiConsumerRunner<>(delegate, t, u));
        }

        private static final class BiConsumerRunner<T, U> implements Runnable {
            private final BiConsumer<T, U> consumer;
            private final T arg1;
            private final U arg2;

            BiConsumerRunner(BiConsumer<T, U> consumer, T arg1, U arg2) {
                this.consumer = consumer;
                this.arg1 = arg1;
                this.arg2 = arg2;
            }

            @Override
            public void run() {
                consumer.accept(arg1, arg2);
            }
        }
    }

    private static final class WrappedSupplier<T> implements Supplier<T> {
        private final OpentelemetryContext ctx;
        private final Supplier<T> delegate;

        WrappedSupplier(OpentelemetryContext ctx, Supplier<T> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public T get() {
            return ScopedValue.where(VALUE, ctx).call(new SupplierRunner<>(delegate));
        }

        private static final class SupplierRunner<T> implements ScopedValue.CallableOp<T, RuntimeException> {
            private final Supplier<T> supplier;

            SupplierRunner(Supplier<T> supplier) {
                this.supplier = supplier;
            }

            @Override
            public T call() {
                return supplier.get();
            }
        }
    }
}
