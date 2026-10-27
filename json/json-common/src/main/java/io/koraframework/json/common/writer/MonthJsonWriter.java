package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.Month;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class MonthJsonWriter implements JsonWriter<Month> {
    @Override
    public void write(JsonGenerator gen, Month object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.name());
        }
    }
}
