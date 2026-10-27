package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.YearMonth;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class YearMonthJsonWriter implements JsonWriter<YearMonth> {
    @Override
    public void write(JsonGenerator gen, YearMonth object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_YEAR_MONTH));
        }
    }
}
