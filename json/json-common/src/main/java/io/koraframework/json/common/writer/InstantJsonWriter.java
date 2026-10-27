package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class InstantJsonWriter implements JsonWriter<Instant> {
    @Override
    public void write(JsonGenerator gen, Instant object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(DateTimeFormatter.ISO_INSTANT.format(object));
        }
    }
}
