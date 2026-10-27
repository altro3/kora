package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.MonthDay;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class MonthDayJsonWriter implements JsonWriter<MonthDay> {
    @Override
    public void write(JsonGenerator gen, MonthDay object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_MONTH_DAY));
        }
    }
}
