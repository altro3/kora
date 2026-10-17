package io.koraframework.application.graph.internal;

import io.koraframework.application.graph.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class AllPromisesImpl<T> implements All<PromiseOf<T>> {
    private final List<PromiseOf<T>> values;

    @SafeVarargs
    public AllPromisesImpl(Graph graph, NodeWithMapper<?, T>... nodes) {
        var list = new ArrayList<PromiseOf<T>>(nodes.length);
        for (var node : nodes) {
            var condition = node.node().condition();
            if (condition == null || condition.apply(graph) instanceof GraphCondition.ConditionResult.Matched) {
                list.add(getPromise(graph, node));
            }
        }
        this.values = List.copyOf(list);
    }

    private static <N, V> PromiseOf<V> getPromise(Graph graph, NodeWithMapper<N, V> element) {
        var promise = graph.promiseOf(element.node());
        var mapper = element.mapper();
        return promise.map(mapper::apply);
    }

    @Override
    public Iterator<PromiseOf<T>> iterator() {
        return values.iterator();
    }
}
