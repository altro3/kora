package io.koraframework.json.common.writer;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;

import java.time.*;

public class OffsetTimeJsonWriter implements JsonWriter<OffsetTime> {
    @Override
    public void write(JsonGenerator gen, OffsetTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_OFFSET_TIME));
        }
    }

    @Override
    public void write(JSONWriter writer, OffsetTime object) {
        if (object == null) {
            writer.writeNull();
        } else {
            writer.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_OFFSET_TIME));
        }
    }
}
