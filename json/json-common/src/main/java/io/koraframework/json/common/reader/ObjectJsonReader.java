package io.koraframework.json.common.reader;

import io.koraframework.json.common.*;
import io.koraframework.json.common.util.*;
import tools.jackson.core.*;

public class ObjectJsonReader implements JsonReader<Object> {
    @Override
    public Object read(JsonParser parser) throws JacksonException {
        return JsonObjectCodec.parse(parser);
    }
}
