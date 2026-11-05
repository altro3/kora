package io.koraframework.json.common.reader;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;
import tools.jackson.core.exc.*;

import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class IntegerJsonReader implements JsonReader<Integer> {
    @Override
    public Integer read(JsonParser parser) throws JacksonException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new StreamReadException(parser, "Failed to read json: expected an integer number, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
    }

    @Override
    public Integer read(JSONReader reader) {
        if (reader.wasNull()) {
            return null;
        }
        try {
            return reader.readInt32();
        } catch (JSONException e) {
            throw new JSONException(reader.info("Failed to read json: expected an integer number"));
        }
    }
}
