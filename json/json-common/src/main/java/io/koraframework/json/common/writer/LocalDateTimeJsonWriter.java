package io.koraframework.json.common.writer;

import io.koraframework.json.common.*;
import tools.jackson.core.*;
import java.time.LocalDateTime;
import static io.koraframework.json.common.util.JsonModuleUtil.*;

public class LocalDateTimeJsonWriter implements JsonWriter<LocalDateTime> {
    @Override
    public void write(JsonGenerator gen, LocalDateTime object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.format(KoraDateTimeFormatters.KORA_ISO_LOCAL_DATE_TIME));
        }
    }
}
