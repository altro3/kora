package io.koraframework.json.jackson.module.http;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.writer.*;
import io.koraframework.http.common.body.*;

import java.io.*;

public class FastJsonHttpBodyOutput<T> implements HttpBodyOutput {
    private final ObjectWriter<T> objectWriter;
    private final T value;

    public FastJsonHttpBodyOutput(ObjectWriter<T> objectWriter, T value) {
        this.objectWriter = objectWriter;
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
    public void write(OutputStream os) throws IOException {
        try (var writer = JSONWriter.ofUTF8()) {
            writer.setRootObject(this.value);
            this.objectWriter.write(writer, this.value, null, null, 0);
            writer.flushTo(os);
        } catch (Exception e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
    }
}
