package io.koraframework.json.common.reader;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;
import tools.jackson.core.exc.*;

import java.util.*;

import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class SetJsonReader<T> implements JsonReader<Set<T>> {
    private final JsonReader<T> reader;

    public SetJsonReader(JsonReader<T> reader) {
        this.reader = reader;
    }

    @Override
    public Set<T> read(JsonParser parser) {
        var token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.START_ARRAY) {
            throw new StreamReadException(parser, "Failed to read json array: expected an array, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
        }
        token = parser.nextToken();
        if (token == JsonToken.END_ARRAY) {
            return Set.of();
        }

        Set<T> result = new LinkedHashSet<>();
        while (token != JsonToken.END_ARRAY) {
            var element = this.reader.read(parser);
            result.add(element);
            token = parser.nextToken();
        }

        return result;
    }

    @Override
    public Set<T> read(JSONReader reader) {
        if (reader.wasNull()) {
            return null;
        }
        try {
            if (!reader.nextIfArrayStart()) {
                throw new JSONException("Expected array start");
            }
            if (reader.nextIfArrayEnd()) {
                return Set.of();
            }
            Set<T> result = new LinkedHashSet<>();
            while (!reader.nextIfArrayEnd()) {
                result.add(this.reader.read(reader));
            }
            return result;
        } catch (JSONException e) {
            throw new JSONException(reader.info("Failed to read json array: expected an array"));
        }
    }
}
