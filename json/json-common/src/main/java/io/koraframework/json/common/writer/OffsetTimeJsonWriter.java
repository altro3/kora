package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.OffsetTime;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class OffsetTimeJsonWriter implements JsonWriter<OffsetTime> {
    @Override
    public void write(JsonGenerator gen, OffsetTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_OFFSET_TIME));
        }
    }
}
