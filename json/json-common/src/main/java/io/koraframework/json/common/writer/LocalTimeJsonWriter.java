package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.LocalTime;
import static io.koraframework.json.common.util.JsonModuleUtil.*;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;

public class LocalTimeJsonWriter implements JsonWriter<LocalTime> {
    @Override
    public void write(JsonGenerator gen, LocalTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(ISO_LOCAL_TIME));
        }
    }
}
