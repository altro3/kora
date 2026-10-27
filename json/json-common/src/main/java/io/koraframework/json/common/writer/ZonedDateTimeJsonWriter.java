package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.ZonedDateTime;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class ZonedDateTimeJsonWriter implements JsonWriter<ZonedDateTime> {
    @Override
    public void write(JsonGenerator gen, ZonedDateTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_ZONED_DATE_TIME));
        }
    }
}
