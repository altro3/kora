package io.koraframework.application.graph.internal;

import io.koraframework.application.graph.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Function;

public final class AllValuesImpl<T> implements All<ValueOf<T>> {
    private final Graph graph;
    private final ArrayList<ValueWrapper<?, T>> values;

    @SafeVarargs
    public AllValuesImpl(Graph graph, NodeWithMapper<?, T>... nodes) {
        this.graph = graph;
        this.values = new ArrayList<>(nodes.length);
        for (var node : nodes) {
            this.values.add(new ValueWrapper<>(graph, node));
        }
    }

    @Override
    public Iterator<ValueOf<T>> iterator() {
        var list = new ArrayList<ValueOf<T>>();
        for (var value : this.values) {
            var condition = value.node().condition();
            if (condition == null || condition.apply(graph) instanceof GraphCondition.ConditionResult.Matched) {
                list.add(value.get());
            }
        }
        return list.iterator();
    }

    private static final class ValueWrapper<N, V> {
        private final ValueOf<Object> value;
        private final Function<Object, Object> mapper;
        private final Node<? extends N> node;

        @SuppressWarnings("unchecked")
        public ValueWrapper(Graph graph, NodeWithMapper<N, V> element) {
            this.value = graph.valueOf(element.node());
            this.mapper = (Function<Object, Object>) element.mapper();
            this.node = element.node();
        }

        public Node<? extends N> node() {
            return node;
        }

        @SuppressWarnings("unchecked")
        public ValueOf<V> get() {
            return (ValueOf<V>) value.map(new WrapperMapper(mapper));
        }
    }

    private static final class WrapperMapper implements Function<Object, Object> {
        private final Function<Object, Object> mapper;

        public WrapperMapper(Function<Object, Object> mapper) {
            this.mapper = mapper;
        }

        @Override
        public Object apply(Object n) {
            return mapper.apply(n);
        }
    }
}
