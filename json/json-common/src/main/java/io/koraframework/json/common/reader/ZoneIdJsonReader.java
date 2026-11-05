package io.koraframework.json.common.reader;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONException;
import io.koraframework.json.common.*;
import tools.jackson.core.*;
import tools.jackson.core.exc.StreamReadException;

import java.time.ZoneId;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class ZoneIdJsonReader implements JsonReader<ZoneId> {
    @Override
    public ZoneId read(JsonParser parser) throws JacksonException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return ZoneId.of(parser.getValueAsString());
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new StreamReadException(parser, "Failed to read json: expected a string, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
    }

    @Override
    public ZoneId read(JSONReader reader) {
        if (reader.wasNull()) {
            return null;
        }
        try {
            return ZoneId.of(reader.readString());
        } catch (Exception e) {
            throw new JSONException(reader.info("Failed to read json: expected a string"));
        }
    }
}
