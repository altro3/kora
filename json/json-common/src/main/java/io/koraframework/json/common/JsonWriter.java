package io.koraframework.json.common;

import com.alibaba.fastjson2.*;
import io.koraframework.common.annotation.*;
import org.jspecify.annotations.*;
import tools.jackson.core.*;

import java.nio.charset.*;

/**
 * <b>Русский</b>: Контракт писателя JSON со всеми методами записи
 * <hr>
 * <b>English</b>: JSON writer contract with all write methods
 */
public interface JsonWriter<T> extends Mapping.MappingFunction {

    /**
     * @param generator jackson generator that will be used for writing object to JSON
     * @param object    to serialize into JSON
     */
    void write(JsonGenerator generator, @Nullable T object) throws JacksonException;

    default void write(JSONWriter writer, @Nullable T object) {
        throw new UnsupportedOperationException("This writer does not support fastjson2 JSONWriter directly");
    }

    default byte[] toByteArray(@Nullable T value) throws JacksonException {
        JSONWriter.Context context = JsonPools.borrowWriterContext();
        try (var writer = JSONWriter.ofUTF8(context)) {
            this.write(writer, value);
            return writer.getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new RuntimeException("Fastjson2 serialization failed", e);
        } finally {
            JsonPools.returnWriterContext(context);
        }
    }

    default String toString(@Nullable T value) throws JacksonException {
        return toString(value, false);
    }

    default String toPrettyString(@Nullable T value) throws JacksonException {
        return toString(value, true);
    }

    private String toString(@Nullable T value, boolean usePrettyPrinter) throws JacksonException {
        JSONWriter.Context context = JsonPools.borrowWriterContext();
        try (var writer = usePrettyPrinter
            ? JSONWriter.of(context.getProvider(), JSONWriter.Feature.PrettyFormat)
            : JSONWriter.of(context)) {
            this.write(writer, value);
            return writer.toString();
        } catch (JSONException e) {
            throw new RuntimeException("Fastjson2 string serialization failed", e);
        } finally {
            JsonPools.returnWriterContext(context);
        }
    }
}
