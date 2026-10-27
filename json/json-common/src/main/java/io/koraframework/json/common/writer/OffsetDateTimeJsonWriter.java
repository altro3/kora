package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.OffsetDateTime;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class OffsetDateTimeJsonWriter implements JsonWriter<OffsetDateTime> {
    @Override
    public void write(JsonGenerator gen, OffsetDateTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_OFFSET_DATE_TIME));
        }
    }
}
