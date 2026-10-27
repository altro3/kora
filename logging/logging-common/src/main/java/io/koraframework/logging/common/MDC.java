package io.koraframework.logging.common;

import io.koraframework.logging.common.arg.*;
import tools.jackson.core.*;

import java.util.*;

public class MDC {

    private volatile Map<String, StructuredArgumentWriter> values;

    public static final ScopedValue<MDC> VALUE = ScopedValue.newInstance();

    public MDC() {
        this.values = Map.of();
    }

    public MDC(Map<String, StructuredArgumentWriter> values) {
        this.values = Map.copyOf(values);
    }

    public MDC fork() {
        return new MDC(values);
    }

    public Map<String, StructuredArgumentWriter> values() {
        return this.values;
    }

    public void remove0(String key) {
        if (this.values.containsKey(key)) {
            var copy = new HashMap<>(this.values);
            copy.remove(key);
            this.values = Map.copyOf(copy);
        }
    }

    public void put0(String key, StructuredArgumentWriter value) {
        var copy = new HashMap<>(this.values);
        copy.put(key, value);
        this.values = Map.copyOf(copy);
    }

    public void put0(String key, Integer value) {
        if (value == null) {
            this.put0(key, NullStructuredArgumentWriter.INSTANCE);
        } else {
            this.put0(key, new IntStructuredArgumentWriter(value));
        }
    }

    public void put0(String key, int value) {
        this.put0(key, new IntStructuredArgumentWriter(value));
    }

    public void put0(String key, Long value) {
        if (value == null) {
            this.put0(key, NullStructuredArgumentWriter.INSTANCE);
        } else {
            this.put0(key, new LongStructuredArgumentWriter(value));
        }
    }

    public void put0(String key, long value) {
        this.put0(key, new LongStructuredArgumentWriter(value));
    }

    public void put0(String key, String value) {
        if (value == null) {
            this.put0(key, NullStructuredArgumentWriter.INSTANCE);
        } else {
            this.put0(key, new StringStructuredArgumentWriter(value));
        }
    }

    public void put0(String key, Boolean value) {
        if (value == null) {
            this.put0(key, NullStructuredArgumentWriter.INSTANCE);
        } else {
            this.put0(key, new BooleanStructuredArgumentWriter(value));
        }
    }

    public void put0(String key, boolean value) {
        this.put0(key, new BooleanStructuredArgumentWriter(value));
    }

    public static MDC get() {
        return VALUE.get();
    }

    public static void put(String key, String value) {
        get().put0(key, value);
    }

    public static void put(String key, Integer value) {
        get().put0(key, value);
    }

    public static void put(String key, int value) {
        get().put0(key, value);
    }

    public static void put(String key, Long value) {
        get().put0(key, value);
    }

    public static void put(String key, long value) {
        get().put0(key, value);
    }

    public static void put(String key, Boolean value) {
        get().put0(key, value);
    }

    public static void put(String key, boolean value) {
        get().put0(key, value);
    }

    public static void put(String key, StructuredArgumentWriter value) {
        get().put0(key, value);
    }

    public static void remove(String key) {
        get().remove0(key);
    }

    public static final class NullStructuredArgumentWriter implements StructuredArgumentWriter {
        public static final NullStructuredArgumentWriter INSTANCE = new NullStructuredArgumentWriter();

        private NullStructuredArgumentWriter() {}

        @Override
        public void writeTo(JsonGenerator gen) {
            gen.writeNull();
        }
    }

    public static final class IntStructuredArgumentWriter implements StructuredArgumentWriter {
        private final int val;

        public IntStructuredArgumentWriter(int val) {
            this.val = val;
        }

        @Override
        public void writeTo(JsonGenerator gen) {
            gen.writeNumber(this.val);
        }
    }

    public static final class LongStructuredArgumentWriter implements StructuredArgumentWriter {
        private final long val;

        public LongStructuredArgumentWriter(long val) {
            this.val = val;
        }

        @Override
        public void writeTo(JsonGenerator gen) {
            gen.writeNumber(this.val);
        }
    }

    public static final class StringStructuredArgumentWriter implements StructuredArgumentWriter {
        private final String val;

        public StringStructuredArgumentWriter(String val) {
            this.val = val;
        }

        @Override
        public void writeTo(JsonGenerator gen) {
            gen.writeString(this.val);
        }
    }

    public static final class BooleanStructuredArgumentWriter implements StructuredArgumentWriter {
        private final boolean val;

        public BooleanStructuredArgumentWriter(boolean val) {
            this.val = val;
        }

        @Override
        public void writeTo(JsonGenerator gen) {
            gen.writeBoolean(this.val);
        }
    }
}
