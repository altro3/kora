package io.koraframework.json.common.writer;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;

import java.time.*;

public class ZoneIdJsonWriter implements JsonWriter<ZoneId> {
    @Override
    public void write(JsonGenerator gen, ZoneId object) throws JacksonException {
        if (object == null) {
            gen.writeNull();
        } else {
            gen.writeString(object.getId());
        }
    }

    @Override
    public void write(JSONWriter writer, ZoneId object) {
        if (object == null) {
            writer.writeNull();
        } else {
            writer.writeString(object.getId());
        }
    }
}
