package io.koraframework.http.server.common.request.mapper;

import com.alibaba.fastjson2.*;
import io.koraframework.http.server.common.request.*;
import io.koraframework.http.server.common.response.*;
import io.koraframework.json.common.*;

import java.io.*;

public final class JsonReaderHttpServerRequestMapper<T> implements HttpServerRequestMapper<T> {
    private final io.koraframework.json.common.JsonReader<T> reader;

    public JsonReaderHttpServerRequestMapper(io.koraframework.json.common.JsonReader<T> reader) {
        this.reader = reader;
    }

    @Override
    public T apply(HttpServerRequest request) throws IOException {
        try (var body = request.body()) {
            var fullContent = body.getFullContentIfAvailable();
            if (fullContent != null) {
                if (fullContent.hasArray()) {
                    return this.reader.read(fullContent.array(), fullContent.arrayOffset() + fullContent.position(), fullContent.remaining());
                } else {
                    final byte[] initialBuf = JsonPools.borrowBuffer();
                    byte[] buf = initialBuf;
                    try {
                        int remaining = fullContent.remaining();
                        if (remaining > buf.length) {
                            buf = new byte[remaining];
                        }
                        fullContent.get(buf, 0, remaining);
                        return this.reader.read(buf, 0, remaining);
                    } finally {
                        JsonPools.returnBuffer(initialBuf);
                    }
                }
            }

            final byte[] initialBuf = JsonPools.borrowBuffer();
            byte[] finalBuf;
            try {
                var result = body.readAllBytes(initialBuf);
                finalBuf = result.bytes();

                if (result.length() == 0) {
                    return null;
                }

                return this.reader.read(finalBuf, 0, result.length());
            } finally {
                JsonPools.returnBuffer(initialBuf);
            }
        } catch (JSONException e) {
            throw HttpServerResponseException.of(400, e);
        }
    }
}
