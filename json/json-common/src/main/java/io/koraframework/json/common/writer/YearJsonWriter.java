package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.Year;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class YearJsonWriter implements JsonWriter<Year> {
    @Override
    public void write(JsonGenerator gen, Year object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_YEAR));
        }
    }
}
