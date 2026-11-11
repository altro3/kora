package io.koraframework.http.server.netty;

import io.koraframework.http.server.netty.buffer.*;
import io.netty.channel.*;
import io.netty.util.internal.shaded.org.jctools.queues.*;
import org.jspecify.annotations.*;

import java.util.concurrent.Flow.*;
import java.util.concurrent.atomic.*;

public final class KoraStreamingNettyBody implements Publisher<KoraReadBuffer>, AutoCloseable {

    private final EventLoop eventLoop;
    private final ChannelHandlerContext ctx;

    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final MpscChunkedArrayQueue<KoraReadBuffer> queue = new MpscChunkedArrayQueue<>(128, 4096);

    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    @Nullable
    private volatile Throwable error = null;
    @Nullable
    private volatile VolatileSubscription currentSubscription = null;

    public KoraStreamingNettyBody(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.eventLoop = ctx.channel().eventLoop();
    }

    public void add(KoraReadBuffer buffer) {
        if (cancelled.get()) {
            buffer.close();
            return;
        }
        queueSize.incrementAndGet();
        queue.offer(buffer);
        drain();
    }

    public void complete() {
        completed.set(true);
        drain();
    }

    public void error(Throwable t) {
        error = t;
        drain();
    }

    @Override
    public void subscribe(Subscriber<? super KoraReadBuffer> subscriber) {
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onError(new IllegalStateException("ByteBody can only be subscribed to once."));
            return;
        }

        VolatileSubscription sub = new VolatileSubscription(subscriber);
        this.currentSubscription = sub;
        subscriber.onSubscribe(sub);
    }

    private void drain() {
        VolatileSubscription sub = currentSubscription;
        if (sub != null) {
            sub.drain();
        }
    }

    @Override
    public void close() {
        if (cancelled.compareAndSet(false, true)) {
            KoraReadBuffer rb;
            while ((rb = queue.poll()) != null) {
                queueSize.decrementAndGet();
                rb.close();
            }
        }
    }

    public boolean isWritable() {
        return queueSize.get() < 32;
    }

    private final class VolatileSubscription implements Subscription {
        private final Subscriber<? super KoraReadBuffer> subscriber;
        private final AtomicLong requested = new AtomicLong(0);
        private final AtomicBoolean draining = new AtomicBoolean(false);

        public VolatileSubscription(Subscriber<? super KoraReadBuffer> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Spec rule 3.9: n must be > 0"));
                return;
            }

            requested.updateAndGet(current -> (current + n < 0) ? Long.MAX_VALUE : current + n);
            if (!eventLoop.inEventLoop()) {
                eventLoop.execute(ctx::read);
            } else {
                ctx.read();
            }

            drain();
        }

        @Override
        public void cancel() {
            KoraStreamingNettyBody.this.close();
        }

        void drain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }

            try {
                long req = requested.get();
                long emitted = 0;

                while (emitted != req) {
                    if (cancelled.get()) {
                        return;
                    }

                    Throwable t = error;
                    if (t != null) {
                        subscriber.onError(t);
                        return;
                    }

                    KoraReadBuffer buffer = queue.poll();
                    boolean empty = (buffer == null);

                    if (completed.get() && empty) {
                        subscriber.onComplete();
                        return;
                    }

                    if (empty) {
                        break;
                    }

                    queueSize.decrementAndGet();

                    if (cancelled.get()) {
                        buffer.close();
                        return;
                    }

                    try {
                        subscriber.onNext(buffer);
                    } catch (Throwable e) {
                        buffer.close();
                        throw e;
                    }
                    emitted++;
                }

                if (emitted != 0) {
                    requested.addAndGet(-emitted);
                    if (!cancelled.get() && !completed.get()) {
                        if (eventLoop.inEventLoop()) {
                            ctx.read();
                        } else {
                            eventLoop.execute(ctx::read);
                        }
                    }
                }
            } finally {
                draining.set(false);
            }
        }
    }
}
