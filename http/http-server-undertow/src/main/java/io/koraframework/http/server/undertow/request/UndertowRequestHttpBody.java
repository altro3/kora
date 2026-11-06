package io.koraframework.http.server.undertow.request;

import io.undertow.connector.PooledByteBuffer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.koraframework.http.common.body.HttpBodyInput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class UndertowRequestHttpBody implements HttpBodyInput {

    private final HttpServerExchange exchange;

    public UndertowRequestHttpBody(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public long contentLength() {
        var contentLengthStr = this.exchange.getRequestHeaders().getFirst(Headers.CONTENT_LENGTH);
        return contentLengthStr == null ? -1 : Long.parseLong(contentLengthStr);
    }

    @Nullable
    @Override
    public String contentType() {
        return this.exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
    }

    @Override
    @NonNull
    public InputStream asInputStream() {
        if (!this.exchange.isBlocking()) {
            this.exchange.startBlocking();
        }
        return this.exchange.getInputStream();
    }

    @Override
    public ReadResult readAllBytes(byte[] initialBuffer) throws IOException {
        var channel = exchange.getRequestChannel();
        if (channel == null) {
            return new ReadResult(initialBuffer, 0);
        }

        byte[] buf = initialBuffer;
        int offset = 0;

        var nioBuf = ByteBuffer.wrap(buf);
        var dsts = new ByteBuffer[] { nioBuf };

        while (true) {
            long read = channel.read(dsts, 0, 1);

            if (read > 0) {
                offset += read;

                if (offset == buf.length) {
                    var newBuf = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, newBuf, 0, offset);
                    buf = newBuf;

                    nioBuf = ByteBuffer.wrap(buf, offset, buf.length - offset);
                    dsts[0] = nioBuf;
                }
            } else if (read == -1) {
                break;
            } else {
                Thread.yield();
            }
        }

        return new ReadResult(buf, offset);
    }

    @Override
    public void close() throws IOException {
        this.asInputStream().close();
    }

    @Override
    public String toString() {
        return exchange.toString();
    }
}
