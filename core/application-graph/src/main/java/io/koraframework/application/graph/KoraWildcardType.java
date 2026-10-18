package io.koraframework.application.graph;

import org.jspecify.annotations.*;

import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

public final class KoraWildcardType implements WildcardType {
    private static final Type[] EMPTY_TYPES = new Type[0];
    private final Type[] upperBounds;
    private final Type[] lowerBounds;

    public KoraWildcardType(Type @Nullable [] upperBounds, Type @Nullable [] lowerBounds) {
        this.upperBounds = upperBounds != null ? upperBounds : new Type[]{Object.class};
        this.lowerBounds = lowerBounds != null ? lowerBounds : EMPTY_TYPES;
    }

    @Override
    public Type[] getUpperBounds() {
        return upperBounds.clone();
    }

    @Override
    public Type[] getLowerBounds() {
        return lowerBounds.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WildcardType that)) return false;
        return Arrays.equals(upperBounds, that.getUpperBounds()) &&
            Arrays.equals(lowerBounds, that.getLowerBounds());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(lowerBounds) ^ Arrays.hashCode(upperBounds);
    }

    @Override
    public String toString() {
        if (lowerBounds.length > 0) return "? super " + lowerBounds[0].getTypeName();
        if (upperBounds.length > 0 && !upperBounds[0].equals(Object.class)) return "? extends " + upperBounds[0].getTypeName();
        return "?";
    }
}
