package io.koraframework.json.common.writer;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;

import java.time.*;

public class YearMonthJsonWriter implements JsonWriter<YearMonth> {
    @Override
    public void write(JsonGenerator gen, YearMonth object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_YEAR_MONTH));
        }
    }

    @Override
    public void write(JSONWriter writer, YearMonth object) {
        if (object == null) {
            writer.writeNull();
        } else {
            writer.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_YEAR_MONTH));
        }
    }
}
