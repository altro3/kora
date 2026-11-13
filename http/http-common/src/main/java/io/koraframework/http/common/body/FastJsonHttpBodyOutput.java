package io.koraframework.http.common.body;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import org.jspecify.annotations.*;

import java.io.*;
import java.nio.*;
import java.util.function.*;

public final class FastJsonHttpBodyOutput<T> implements HttpBodyOutput {
    private final JsonWriter<T> writer;
    @Nullable
    private final T value;

    public FastJsonHttpBodyOutput(JsonWriter<T> writer, @Nullable T value) {
        this.writer = writer;
        this.value = value;
    }

    @Override
    public long contentLength() {
        return -1;
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public ByteBuffer writeToByteBuffer(IntFunction<ByteBuffer> allocator) throws IOException {
        var context = JsonPools.borrowWriterContext();
        try (var jsonWriter = JSONWriter.ofUTF8(context)) {
            writer.write(jsonWriter, value);
            int size = jsonWriter.size();
            var target = allocator.apply(size);
            jsonWriter.flushTo(new ByteBufferOutputStream(target));
            target.flip();
            return target;
        } catch (JSONException e) {
            throw new IOException("Fastjson2 serialization failed", e);
        } finally {
            JsonPools.returnWriterContext(context);
        }
    }

    @Override
    public void write(OutputStream os) throws IOException {
        var context = JsonPools.borrowWriterContext();
        try (var jsonWriter = JSONWriter.ofUTF8(context)) {
            writer.write(jsonWriter, value);
            jsonWriter.flushTo(os);
        } catch (JSONException e) {
            throw new IOException("Fastjson2 serialization failed", e);
        } finally {
            JsonPools.returnWriterContext(context);
        }
    }

    @Override
    public void close() {
    }

    private static final class ByteBufferOutputStream extends OutputStream {
        private final ByteBuffer buffer;

        ByteBufferOutputStream(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void write(int b) {
            buffer.put((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.put(b, off, len);
        }
    }
}
