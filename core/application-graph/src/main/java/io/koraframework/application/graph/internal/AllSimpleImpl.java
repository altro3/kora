package io.koraframework.application.graph.internal;

import io.koraframework.application.graph.All;
import io.koraframework.application.graph.Graph;
import io.koraframework.application.graph.GraphCondition;
import io.koraframework.application.graph.NodeWithMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class AllSimpleImpl<T> implements All<T> {
    private final List<T> values;

    @SafeVarargs
    public AllSimpleImpl(Graph graph, NodeWithMapper<?, T>... nodes) {
        var list = new ArrayList<T>(nodes.length);
        for (var node : nodes) {
            var condition = node.node().condition();
            if (condition == null || condition.apply(graph) instanceof GraphCondition.ConditionResult.Matched) {
                list.add(get(graph, node));
            }
        }
        this.values = List.copyOf(list);
    }

    private static <N, V> V get(Graph graph, NodeWithMapper<N, V> node) {
        return node.mapper().apply(graph.get(node.node()));
    }

    @Override
    public Iterator<T> iterator() {
        return values.iterator();
    }
}
