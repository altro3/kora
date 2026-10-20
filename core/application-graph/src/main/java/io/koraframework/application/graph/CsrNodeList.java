package io.koraframework.application.graph;

import io.koraframework.application.graph.internal.*;

import java.util.*;

public final class CsrNodeList extends AbstractList<Node<?>> implements RandomAccess {
    private final NodeImpl<?>[] graphNodes;
    private final short[] edges;
    private final short start;
    private final short size;

    public CsrNodeList(NodeImpl<?>[] graphNodes, short[] edges, short start, short end) {
        this.graphNodes = graphNodes;
        this.edges = edges;
        this.start = start;
        this.size = (short) (end - start);
    }

    @Override
    public Node<?> get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return graphNodes[edges[start + index]];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }
}
