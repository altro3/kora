package io.koraframework.application.graph;

import java.lang.reflect.Type;
import java.util.Arrays;

public final class TypeShortMap {
    private Type[] keys;
    private short[] values;
    private int mask;
    private short size;
    private final float loadFactor = 0.5f;

    public TypeShortMap(short capacity) {
        int tableSize = 1;
        while (tableSize < capacity) tableSize <<= 1;
        keys = new Type[tableSize];
        values = new short[tableSize];
        Arrays.fill(values, (short) -1);
        mask = tableSize - 1;
    }

    public void put(Type key, short value) {
        if (size >= keys.length * loadFactor) {
            rehash();
        }
        int idx = hash(key) & mask;
        while (keys[idx] != null) {
            if (keys[idx].equals(key)) {
                values[idx] = value;
                return;
            }
            idx = (idx + 1) & mask;
        }
        keys[idx] = key;
        values[idx] = value;
        size++;
    }

    public short get(Type key) {
        int idx = hash(key) & mask;
        while (keys[idx] != null) {
            if (keys[idx].equals(key)) {
                return values[idx];
            }
            idx = (idx + 1) & mask;
        }
        return -1;
    }

    public TypeShortMap copy() {
        TypeShortMap copy = new TypeShortMap((short) keys.length);
        System.arraycopy(keys, 0, copy.keys, 0, keys.length);
        System.arraycopy(values, 0, copy.values, 0, values.length);
        copy.mask = mask;
        copy.size = size;
        return copy;
    }

    private void rehash() {
        Type[] oldKeys = keys;
        short[] oldValues = values;
        int newCapacity = oldKeys.length << 1;
        
        keys = new Type[newCapacity];
        values = new short[newCapacity];
        Arrays.fill(values, (short) -1);
        mask = newCapacity - 1;
        size = 0;

        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != null) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private int hash(Type key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }
}
