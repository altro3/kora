package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.body.*;
import io.koraframework.http.server.netty.*;
import io.koraframework.http.server.netty.body.*;
import io.koraframework.http.server.netty.buffer.*;
import io.netty.handler.codec.http.*;
import org.jspecify.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class NettyRequestHttpBody implements HttpBodyInput {

    private final HttpRequest nettyRequest;
    @Nullable
    private final AutoCloseable rawBody;
    private final InputStream inputStream;

    public NettyRequestHttpBody(HttpRequest nettyRequest, @Nullable AutoCloseable rawBody) {
        this.nettyRequest = Objects.requireNonNull(nettyRequest, "nettyRequest");
        this.rawBody = rawBody;
        this.inputStream = switch (rawBody) {
            case null -> InputStream.nullInputStream();
            case KoraAvailableBody available -> available.toInputStream();
            case KoraStreamingNettyBody streaming -> new FlowInputStream(streaming);
            default -> throw new IllegalArgumentException("Unsupported body type: " + rawBody.getClass());
        };
    }

    @Override
    public long contentLength() {
        return HttpUtil.getContentLength(nettyRequest, -1L);
    }

    @Nullable
    @Override
    public String contentType() {
        return nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    public InputStream asInputStream() {
        return inputStream;
    }

    @Override
    public void close() {
        try {
            inputStream.close();
        } catch (Exception ignored) {
        }
        if (rawBody != null) {
            try {
                rawBody.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class FlowInputStream extends InputStream implements Flow.Subscriber<KoraReadBuffer> {
        private static final Object COMPLETE_MARKER = new Object();

        private final KoraStreamingNettyBody publisher;
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private volatile Flow.@Nullable Subscription subscription;
        @Nullable
        private KoraReadBuffer currentBuffer;
        @Nullable
        private InputStream currentStream;
        private boolean finished;
        private volatile boolean closed;

        FlowInputStream(KoraStreamingNettyBody publisher) {
            this.publisher = publisher;
            publisher.subscribe(this);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (closed) {
                subscription.cancel();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(KoraReadBuffer item) {
            if (closed) {
                item.close();
                return;
            }
            queue.offer(item);
        }

        @Override
        public void onError(Throwable throwable) {
            queue.offer(throwable);
        }

        @Override
        public void onComplete() {
            queue.offer(COMPLETE_MARKER);
        }

        private boolean ensureCurrentStream() throws IOException {
            if (finished) {
                return false;
            }
            if (currentStream != null) {
                return true;
            }
            Object next;
            try {
                next = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new InterruptedIOException("Interrupted while reading request body");
            }

            if (next == COMPLETE_MARKER) {
                finished = true;
                return false;
            }
            if (next instanceof Throwable t) {
                finished = true;
                throw t instanceof IOException io ? io : new IOException("Request body stream failed", t);
            }

            var buf = (KoraReadBuffer) next;
            currentBuffer = buf;
            currentStream = buf.toInputStream();
            return true;
        }

        private void releaseCurrentAndRequestMore() {
            if (currentStream != null) {
                try {
                    currentStream.close();
                } catch (IOException ignored) {
                }
                currentStream = null;
            }
            if (currentBuffer != null) {
                currentBuffer.close();
                currentBuffer = null;
            }
            var sub = subscription;
            if (sub != null && !closed && !finished) {
                sub.request(1);
            }
        }

        @Override
        public int read() throws IOException {
            while (ensureCurrentStream()) {
                int b = Objects.requireNonNull(currentStream).read();
                if (b != -1) {
                    return b;
                }
                releaseCurrentAndRequestMore();
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            while (ensureCurrentStream()) {
                int read = Objects.requireNonNull(currentStream).read(b, off, len);
                if (read != -1) {
                    return read;
                }
                releaseCurrentAndRequestMore();
            }
            return -1;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            var sub = subscription;
            if (sub != null) {
                sub.cancel();
            }
            releaseCurrentAndRequestMore();
            Object item;
            while ((item = queue.poll()) != null) {
                if (item instanceof KoraReadBuffer rb) {
                    rb.close();
                }
            }
            publisher.close();
        }
    }
}
