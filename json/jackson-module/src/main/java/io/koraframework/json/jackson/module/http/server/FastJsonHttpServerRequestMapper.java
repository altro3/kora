package io.koraframework.json.jackson.module.http.server;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.reader.*;
import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.json.common.JsonPools;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;

public final class FastJsonHttpServerRequestMapper<T> implements HttpServerRequestMapper<T> {
    private final ObjectReader<T> objectReader;

    @SuppressWarnings("unchecked")
    public FastJsonHttpServerRequestMapper(Type type) {
        this.objectReader = (ObjectReader<T>) JSONFactory.getDefaultObjectReaderProvider().getObjectReader(type);
    }

    @Override
    public T apply(HttpServerRequest request) throws IOException {
        try (var body = request.body()) {
            var fullContent = body.getFullContentIfAvailable();
            if (fullContent != null) {
                return parseFromByteBuffer(fullContent);
            }
            final byte[] initialBuf = JsonPools.borrowBuffer();
            byte[] finalBuf;
            JSONReader.Context context = JsonPools.borrowReaderContext();
            try {
                var result = body.readAllBytes(initialBuf);
                finalBuf = result.bytes();

                if (result.length() == 0) {
                    return null;
                }

                try (var reader = JSONReader.of(finalBuf, 0, result.length(), context)) {
                    return objectReader.readObject(reader, null, null, 0);
                }
            } finally {
                JsonPools.returnReaderContext(context);
                JsonPools.returnBuffer(initialBuf);
            }
        } catch (JSONException e) {
            throw HttpServerResponseException.of(400, e);
        }
    }

    private T parseFromByteBuffer(ByteBuffer buffer) {
        JSONReader jsonReader;
        byte[] heapBytes = null;
        JSONReader.Context context = JsonPools.borrowReaderContext();

        if (buffer.hasArray()) {
            jsonReader = JSONReader.of(
                buffer.array(),
                buffer.arrayOffset() + buffer.position(),
                buffer.remaining(),
                context
            );
        } else {
            int remaining = buffer.remaining();
            heapBytes = JsonPools.borrowBuffer();

            if (heapBytes.length < remaining) {
                heapBytes = new byte[remaining];
            }

            ByteBuffer duplicate = buffer.duplicate();
            duplicate.get(heapBytes, 0, remaining);
            jsonReader = JSONReader.of(heapBytes, 0, remaining, context);
        }

        try (jsonReader) {
            return this.objectReader.readObject(jsonReader, null, null, 0);
        } finally {
            JsonPools.returnReaderContext(context);
            if (heapBytes != null) {
                JsonPools.returnBuffer(heapBytes);
            }
        }
    }
}
