package io.koraframework.json.common.reader;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import tools.jackson.core.exc.StreamReadException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class LocalDateJsonReader implements JsonReader<LocalDate> {
    @Override
    public LocalDate read(JsonParser parser) throws JacksonException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return LocalDate.parse(parser.getValueAsString(), DateTimeFormatter.ISO_DATE);
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new StreamReadException(parser, "Failed to read json: expected a string, but got " + actualValue(parser) + " (at " + jsonPath(parser) + ")");
    }
}
