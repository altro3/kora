package io.koraframework.application.graph;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;

public class TypeRef<T> implements ParameterizedType {
    private final Class<T> rawType;
    private final Type[] actualTypeArguments;
    private final int hashCode;

    private TypeRef(Class<T> rawType, Type... actualTypeArguments) {
        this.rawType = rawType;
        this.actualTypeArguments = actualTypeArguments;
        int result = rawType.hashCode();
        result = 31 * result + Arrays.hashCode(actualTypeArguments);
        this.hashCode = result;
    }

    @SuppressWarnings("unchecked")
    public static <T> TypeRef<T> of(Class<? super T> rawType, Type... actualTypeArguments) {
        var args = new Type[actualTypeArguments.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = actualTypeArguments[i] instanceof TypeRef<?> typeRef && typeRef.actualTypeArguments.length == 0
                ? typeRef.rawType
                : actualTypeArguments[i];
        }
        return new TypeRef<>((Class<T>) rawType, args);
    }

    @Override
    public Type[] getActualTypeArguments() {
        return actualTypeArguments;
    }

    @Override
    public Class<T> getRawType() {
        return rawType;
    }

    @Override
    public Type getOwnerType() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ParameterizedType that) {
            if (rawType != that.getRawType()) {
                return false;
            }
            if (that.getOwnerType() != null) {
                return false;
            }
            return Arrays.equals(actualTypeArguments, that.getActualTypeArguments());
        } else if (o instanceof Class<?> type && actualTypeArguments.length == 0) {
            return rawType.equals(type);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        var rawTypeString = rawType.getName();
        if (actualTypeArguments.length == 0) {
            return rawTypeString;
        }
        var sb = new StringBuilder(rawTypeString).append("<");
        for (int i = 0; i < actualTypeArguments.length; i++) {
            Type t = actualTypeArguments[i];
            if (t instanceof Class<?> c) {
                sb.append(c.getName());
            } else {
                sb.append(t.getTypeName());
            }
            if (i < actualTypeArguments.length - 1) {
                sb.append(", ");
            }
        }
        return sb.append(">").toString();
    }
}
