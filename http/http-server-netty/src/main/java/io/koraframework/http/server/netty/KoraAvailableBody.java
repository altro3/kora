package io.koraframework.http.server.netty;

import io.koraframework.http.server.netty.buffer.*;

import java.io.*;
import java.nio.charset.*;
import java.util.concurrent.Flow.*;
import java.util.concurrent.atomic.*;

public final class KoraAvailableBody implements AutoCloseable {

    private final KoraReadBuffer buffer;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    public KoraAvailableBody(KoraReadBuffer buffer) {
        this.buffer = buffer;
    }

    public long length() {
        return buffer.readable();
    }

    public KoraAvailableBody split() {
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

    public Publisher<KoraReadBuffer> toPublisher() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Body has already been consumed");
        }
        return new AvailableBodyPublisher(buffer);
    }

    @Override
    public void close() {
        buffer.close();
    }

    private static final class AvailableBodyPublisher implements Publisher<KoraReadBuffer> {
        private final KoraReadBuffer buffer;

        AvailableBodyPublisher(KoraReadBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void subscribe(Subscriber<? super KoraReadBuffer> subscriber) {
            subscriber.onSubscribe(new AvailableBodySubscription(subscriber, buffer));
        }
    }

    private static final class AvailableBodySubscription implements Subscription {
        private final Subscriber<? super KoraReadBuffer> subscriber;
        private final KoraReadBuffer buffer;
        private final AtomicBoolean done = new AtomicBoolean(false);

        AvailableBodySubscription(Subscriber<? super KoraReadBuffer> subscriber, KoraReadBuffer buffer) {
            this.subscriber = subscriber;
            this.buffer = buffer;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Spec rule 3.9: n must be > 0"));
                return;
            }
            if (done.compareAndSet(false, true)) {
                try {
                    subscriber.onNext(buffer);
                    subscriber.onComplete();
                } catch (Throwable t) {
                    subscriber.onError(t);
                }
            }
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                buffer.close();
            }
        }
    }
}
