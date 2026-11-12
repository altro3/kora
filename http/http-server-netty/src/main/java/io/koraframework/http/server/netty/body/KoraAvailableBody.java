package io.koraframework.http.server.netty.body;

import io.koraframework.http.server.netty.buffer.*;
import io.netty.buffer.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class KoraAvailableBody implements AutoCloseable {

    private final KoraReadBuffer buffer;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    public KoraAvailableBody(KoraReadBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    public long length() {
        return buffer.readable();
    }

    public KoraAvailableBody split() {
        if (consumed.get()) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return new KoraAvailableBody(buffer.duplicate());
    }

    public byte[] toArray() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return buffer.toArray();
    }

    public String toString(Charset charset) {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return buffer.toString(charset);
    }

    public InputStream toInputStream() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return buffer.toInputStream();
    }

    public Flow.Publisher<KoraReadBuffer> toPublisher() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return new AvailableBodyPublisher(buffer);
    }

    public ByteBuf toByteBuf() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return buffer.toByteBuf();
    }

    @Override
    public void close() {
        if (consumed.compareAndSet(false, true)) {
            buffer.close();
        }
    }

    private static final class AvailableBodyPublisher implements Flow.Publisher<KoraReadBuffer> {
        private final KoraReadBuffer buffer;
        private final AtomicBoolean subscribed = new AtomicBoolean(false);

        AvailableBodyPublisher(KoraReadBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super KoraReadBuffer> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("Body publisher can only be subscribed to once"));
                return;
            }
            subscriber.onSubscribe(new AvailableBodySubscription(subscriber, buffer));
        }
    }

    private static final class AvailableBodySubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super KoraReadBuffer> subscriber;
        private final KoraReadBuffer buffer;
        private final AtomicBoolean done = new AtomicBoolean(false);

        AvailableBodySubscription(Flow.Subscriber<? super KoraReadBuffer> subscriber, KoraReadBuffer buffer) {
            this.subscriber = subscriber;
            this.buffer = buffer;
        }

        @Override
        public void request(long n) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (n <= 0) {
                buffer.close();
                subscriber.onError(new IllegalArgumentException("Spec rule 3.9: n must be > 0"));
                return;
            }
            subscriber.onNext(buffer);
            subscriber.onComplete();
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                buffer.close();
            }
        }
    }
}
