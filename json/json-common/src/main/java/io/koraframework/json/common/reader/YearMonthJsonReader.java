package io.koraframework.json.common.reader;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import tools.jackson.core.exc.StreamReadException;

import java.time.YearMonth;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class YearMonthJsonReader implements JsonReader<YearMonth> {
    @Override
    public YearMonth read(JsonParser parser) throws JacksonException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return YearMonth.parse(parser.getValueAsString(), KoraDateTimeFormatters.KORA_ISO_YEAR_MONTH);
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new StreamReadException(parser, "Failed to read json: expected a string, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
    }
}
