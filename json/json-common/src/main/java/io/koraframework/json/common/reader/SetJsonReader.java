package io.koraframework.json.common.reader;

import io.koraframework.json.common.JsonReader;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;

import java.util.LinkedHashSet;
import java.util.Set;

import static io.koraframework.json.common.util.JsonModuleUtil.actualValue;
import static io.koraframework.json.common.util.JsonModuleUtil.jsonPath;

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
}
