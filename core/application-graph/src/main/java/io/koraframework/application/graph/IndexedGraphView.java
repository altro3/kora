package io.koraframework.application.graph;

import io.koraframework.application.graph.internal.NodeImpl;

public final class IndexedGraphView implements RefreshableGraph {
    private final RefreshableGraph delegate;
    private final int[] seenMap;

    public IndexedGraphView(RefreshableGraph delegate, int[] seenMap) {
        this.delegate = delegate;
        this.seenMap = seenMap;
    }

    @Override
    public ApplicationGraphDraw draw() {
        return delegate.draw();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Node<? extends T> node) {
        var nodeImpl = (NodeImpl<?>) node;
        int oldIdx = nodeImpl.index;
        int subIdx = seenMap[oldIdx];
        return (T) delegate.get(nodeImpl.graphDraw.getNodeByIndex(subIdx));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ValueOf<T> valueOf(Node<? extends T> node) {
        var nodeImpl = (NodeImpl<?>) node;
        int oldIdx = nodeImpl.index;
        int subIdx = seenMap[oldIdx];
        return (ValueOf<T>) delegate.valueOf(nodeImpl.graphDraw.getNodeByIndex(subIdx));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> PromiseOf<T> promiseOf(Node<? extends T> node) {
        var nodeImpl = (NodeImpl<?>) node;
        int oldIdx = nodeImpl.index;
        int subIdx = seenMap[oldIdx];
        return (PromiseOf<T>) delegate.promiseOf(nodeImpl.graphDraw.getNodeByIndex(subIdx));
    }


    @Override
    public <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... nodes) {
        return delegate.getOnePromiseOf(nodes);
    }

    @Override
    public void refresh(Node<?> fromNode) {
        var nodeImpl = (NodeImpl<?>) fromNode;
        int oldIdx = nodeImpl.index;
        int subIdx = seenMap[oldIdx];
        delegate.refresh(nodeImpl.graphDraw.getNodeByIndex(subIdx));
    }
}
