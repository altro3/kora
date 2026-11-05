package io.koraframework.json.common.writer;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;

import java.time.*;

public class OffsetDateTimeJsonWriter implements JsonWriter<OffsetDateTime> {
    @Override
    public void write(JsonGenerator gen, OffsetDateTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_OFFSET_DATE_TIME));
        }
    }

    @Override
    public void write(JSONWriter writer, OffsetDateTime object) {
        if (object == null) {
            writer.writeNull();
        } else {
            writer.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_OFFSET_DATE_TIME));
        }
    }
}
