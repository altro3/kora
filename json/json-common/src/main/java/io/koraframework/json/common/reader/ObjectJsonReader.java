package io.koraframework.json.common.reader;

import com.alibaba.fastjson2.*;
import io.koraframework.json.common.*;
import tools.jackson.core.*;

public class ObjectJsonReader implements JsonReader<Object> {
    @Override
    public Object read(JsonParser parser) throws JacksonException {
        return io.koraframework.json.common.util.JsonObjectCodec.parse(parser);
    }

    @Override
    public Object read(JSONReader reader) {
        return reader.readAny();
    }
}
