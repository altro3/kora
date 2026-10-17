package io.koraframework.application.graph;

import java.util.function.Function;

public record NodeWithMapper<N, V>(Node<? extends N> node, Function<N, V> mapper) {
    private static final Function<?, ?> IDENTITY_MAPPER = v -> v;
    private static final Function<?, ?> UNWRAP_MAPPER = v -> ((Wrapped<?>) v).value();

    @SuppressWarnings("unchecked")
    public static <T> NodeWithMapper<T, T> node(Node<? extends T> node) {
        return new NodeWithMapper<>(node, (Function<T, T>) IDENTITY_MAPPER);
    }

    @SuppressWarnings("unchecked")
    public static <V, N extends Wrapped<V>> NodeWithMapper<N, V> unwrap(Node<? extends N> node) {
        return new NodeWithMapper<>(node, (Function<N, V>) UNWRAP_MAPPER);
    }

    @Override
    public String toString() {
        return node.toString();
    }
}
