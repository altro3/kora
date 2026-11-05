package io.koraframework.json.common.reader;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONException;
import io.koraframework.json.common.JsonReader;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;

import java.util.ArrayList;
import java.util.List;

import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class ListJsonReader<T> implements JsonReader<List<T>> {
    private final JsonReader<T> reader;

    public ListJsonReader(JsonReader<T> reader) {
        this.reader = reader;
    }

    @Override
    public List<T> read(JsonParser parser) {
        var token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.START_ARRAY) {
            throw new StreamReadException(parser, "Failed to read json array: expected an array, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
        }
        token = parser.nextToken();
        if (token == JsonToken.END_ARRAY) {
            return List.of();
        }

        List<T> result = new ArrayList<>();
        while (token != JsonToken.END_ARRAY) {
            var element = this.reader.read(parser);
            result.add(element);
            token = parser.nextToken();
        }

        return result;
    }

    @Override
    public List<T> read(JSONReader reader) {
        if (reader.wasNull()) {
            return null;
        }
        try {
            if (!reader.nextIfArrayStart()) {
                throw new JSONException("Expected array start");
            }
            if (reader.nextIfArrayEnd()) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            while (!reader.nextIfArrayEnd()) {
                result.add(this.reader.read(reader));
            }
            return result;
        } catch (JSONException e) {
            throw new JSONException(reader.info("Failed to read json array: expected an array"));
        }
    }
}
