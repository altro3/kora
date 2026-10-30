package io.koraframework.application.graph;

import java.util.function.Function;

public final class SubgraphIndexedCondition implements Function<Graph, GraphCondition.ConditionResult> {
    private final Function<Graph, GraphCondition.ConditionResult> delegate;
    private final short[] seenMap;

    public SubgraphIndexedCondition(Function<Graph, GraphCondition.ConditionResult> delegate, short[] seenMap) {
        this.delegate = delegate;
        this.seenMap = seenMap;
    }

    @Override
    public GraphCondition.ConditionResult apply(Graph graph) {
        if (graph instanceof RefreshableGraph) {
            var refreshable = (RefreshableGraph) graph;
            return delegate.apply(new IndexedGraphView(refreshable, seenMap));
        }
        throw new IllegalArgumentException("Graph implementation must implement RefreshableGraph");
    }
}
