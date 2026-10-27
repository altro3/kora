package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.DayOfWeek;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class DayOfWeekJsonWriter implements JsonWriter<DayOfWeek> {
    @Override
    public void write(JsonGenerator gen, DayOfWeek object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.name());
        }
    }
}
