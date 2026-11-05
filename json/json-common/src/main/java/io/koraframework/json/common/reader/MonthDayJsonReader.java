package io.koraframework.json.common.reader;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;
import tools.jackson.core.exc.*;

import java.time.*;

import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class MonthDayJsonReader implements JsonReader<MonthDay> {
    @Override
    public MonthDay read(JsonParser parser) throws JacksonException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return MonthDay.parse(parser.getValueAsString(), KoraDateTimeFormatters.KORA_ISO_MONTH_DAY);
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new StreamReadException(parser, "Failed to read json: expected a string, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
    }

    @Override
    public MonthDay read(JSONReader reader) {
        if (reader.wasNull()) {
            return null;
        }
        try {
            return MonthDay.parse(reader.readString(), KoraDateTimeFormatters.KORA_ISO_MONTH_DAY);
        } catch (Exception e) {
            throw new JSONException(reader.info("Failed to read json: expected a string"));
        }
    }
}
