package io.koraframework.json.common;

import com.alibaba.fastjson2.*;
import io.koraframework.common.annotation.*;
import org.jspecify.annotations.*;
import tools.jackson.core.*;

import java.io.*;
import java.nio.charset.*;

/**
 * <b>Русский</b>: Контракт читателя JSON со всеми методами чтения
 * <hr>
 * <b>English</b>: JSON reader contract with all read methods
 */
public interface JsonReader<T> extends Mapping.MappingFunction {

    @Nullable
    T read(JsonParser parser) throws JacksonException;

    @Nullable
    default T read(JSONReader reader) {
        throw new UnsupportedOperationException("This reader does not support fastjson2 JSONReader directly");
    }

    @Nullable
    default T read(byte[] bytes) throws JacksonException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (var reader = JSONReader.of(bytes)) {
            return this.read(reader);
        } catch (JSONException e) {
            throw new RuntimeException("Fastjson2 processing failed", e);
        }
    }

    @Nullable
    default T read(byte[] bytes, int offset, int length) throws JacksonException {
        if (bytes == null || length == 0) {
            return null;
        }
        try (var reader = JSONReader.of(bytes, offset, length)) {
            return this.read(reader);
        } catch (JSONException e) {
            throw new RuntimeException("Fastjson2 processing failed", e);
        }
    }

    @Nullable
    default T read(String str) throws JacksonException {
        if (str == null || str.isEmpty()) {
            return null;
        }
        try (var reader = JSONReader.of(str)) {
            return this.read(reader);
        } catch (JSONException e) {
            throw new RuntimeException("Fastjson2 processing failed", e);
        }
    }

    @Nullable
    default T read(InputStream is) throws JacksonException {
        if (is == null) {
            return null;
        }
        byte[] buf = JsonPools.borrowBuffer();
        try {
            int offset = 0;
            while (true) {
                int read = is.read(buf, offset, buf.length - offset);
                if (read == -1) break;
                offset += read;
                if (offset == buf.length) {
                    var newBuf = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, newBuf, 0, offset);
                    buf = newBuf;
                }
            }
            if (offset == 0) return null;
            try (var reader = JSONReader.of(buf, 0, offset)) {
                return this.read(reader);
            }
        } catch (IOException | JSONException e) {
            throw new RuntimeException("Fastjson2 processing failed", e);
        } finally {
            JsonPools.returnBuffer(buf);
        }
    }
}
