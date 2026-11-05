package io.koraframework.json.common.writer;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;

import java.time.*;

public class ZonedDateTimeJsonWriter implements JsonWriter<ZonedDateTime> {
    @Override
    public void write(JsonGenerator gen, ZonedDateTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_ZONED_DATE_TIME));
        }
    }

    @Override
    public void write(JSONWriter writer, ZonedDateTime object) {
        if (object == null) {
            writer.writeNull();
        } else {
            writer.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_ZONED_DATE_TIME));
        }
    }
}
