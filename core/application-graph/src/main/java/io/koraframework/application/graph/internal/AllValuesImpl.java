package io.koraframework.application.graph.internal;

import io.koraframework.application.graph.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class AllValuesImpl<T> implements All<ValueOf<T>> {
    private final List<ValueOf<T>> values;

    @SafeVarargs
    public AllValuesImpl(Graph graph, NodeWithMapper<?, T>... nodes) {
        var list = new ArrayList<ValueOf<T>>(nodes.length);
        for (var node : nodes) {
            var condition = node.node().condition();
            if (condition == null || condition.apply(graph) instanceof GraphCondition.ConditionResult.Matched) {
                list.add(getValue(graph, node));
            }
        }
        values = List.copyOf(list);
    }

    private static <N, V> ValueOf<V> getValue(Graph graph, NodeWithMapper<N, V> element) {
        var value = graph.valueOf(element.node());
        var mapper = element.mapper();
        return value.map(mapper::apply);
    }

    @Override
    public Iterator<ValueOf<T>> iterator() {
        return values.iterator();
    }
}
