package io.koraframework.json.jackson.module.http.server;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.reader.*;
import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;

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

            try (var is = body.asInputStream()) {
                byte[] readBytes = is.readAllBytes();
                if (readBytes.length == 0) {
                    return null;
                }
                try (var reader = JSONReader.of(readBytes, 0, readBytes.length)) {
                    return this.objectReader.readObject(reader, null, null, 0);
                }
            }
        } catch (JSONException e) {
            throw HttpServerResponseException.of(400, e);
        }
    }

    private T parseFromByteBuffer(ByteBuffer buffer) {
        JSONReader jsonReader;

        if (buffer.hasArray()) {
            jsonReader = JSONReader.of(
                buffer.array(),
                buffer.arrayOffset() + buffer.position(),
                buffer.remaining()
            );
        } else {
            int remaining = buffer.remaining();
            var bytes = new byte[remaining];
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.get(bytes);
            jsonReader = JSONReader.of(bytes, 0, remaining);
        }
        try (jsonReader) {
            return this.objectReader.readObject(jsonReader, null, null, 0);
        }
    }
}
