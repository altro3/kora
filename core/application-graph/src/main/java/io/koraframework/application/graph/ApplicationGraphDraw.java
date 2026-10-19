package io.koraframework.application.graph;

import io.koraframework.application.graph.internal.*;
import io.koraframework.application.graph.internal.optimized.*;
import org.jspecify.annotations.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

public class ApplicationGraphDraw {

    private static final String ANY_TAG_NAME = "io.koraframework.common.Tag.Any";
    private static final @Nullable Class<?> ANY_TAG_CLASS;

    static {
        Class<?> clazz = null;
        try {
            clazz = Class.forName(ANY_TAG_NAME);
        } catch (ClassNotFoundException _) {}
        ANY_TAG_CLASS = clazz;
    }

    private final Class<?> root;
    private final NodeImpl<?>[] graphNodes;
    private int size = 0;

    private final int[] createEdges;
    private int createEdgesSize = 0;
    private final int[] createOffsets;

    private final int[] refreshEdges;
    private int refreshEdgesSize = 0;
    private final int[] refreshOffsets;

    private final int[] downstreamEdges;
    private int downstreamEdgesSize = 0;
    private final int[] downstreamOffsets;

    private final int[] interceptorEdges;
    private int interceptorEdgesSize = 0;
    private final int[] interceptorOffsets;
    private final Type[] nodeTypes;
    private final @Nullable Class<?>[] nodeTags;
    private final @Nullable Function<Graph, GraphCondition.ConditionResult>[] nodeConditions;

    private final Map<Type, int[]> typeIndex;

    public ApplicationGraphDraw(Class<?> root, int expectedNodes, int expectedCreateEdges, int expectedRefreshEdges, int expectedInterceptorEdges, int expectedDownstreamEdges) {
        this.root = root;
        this.graphNodes = new NodeImpl<?>[expectedNodes];
        this.createOffsets = new int[expectedNodes + 1];
        this.refreshOffsets = new int[expectedNodes + 1];
        this.interceptorOffsets = new int[expectedNodes + 1];
        this.downstreamOffsets = new int[expectedNodes + 1];

        this.createEdges = new int[expectedCreateEdges];
        this.refreshEdges = new int[expectedRefreshEdges];
        this.interceptorEdges = new int[expectedInterceptorEdges];
        this.downstreamEdges = new int[expectedDownstreamEdges];

        this.typeIndex = new HashMap<>(expectedNodes);
        this.nodeTypes = new Type[expectedNodes];
        this.nodeTags = new Class<?>[expectedNodes];
        this.nodeConditions = new Function[expectedNodes];
    }

    public Class<?> getRoot() {
        return root;
    }

    public NodeImpl<?> getNodeByIndex(int index) {
        return graphNodes[index];
    }

    public int size() {
        return size;
    }

    public int getCreateDependenciesCount(int nodeIndex) {
        return createOffsets[nodeIndex + 1] - createOffsets[nodeIndex];
    }

    public <T> Node<T> addNode(
        Type type,
        @Nullable Class<?> tag,
        @Nullable Function<Graph, GraphCondition.ConditionResult> condition,
        int @Nullable [] createDependencies,
        int @Nullable [] refreshDependencies,
        int @Nullable [] downstreamConsumers,
        NodeImpl<?> @Nullable [] interceptors,
        Graph.Factory<? extends T> factory
    ) {
        int currentIndex = size;

        createOffsets[currentIndex] = createEdgesSize;
        if (createDependencies != null && createDependencies.length > 0) {
            System.arraycopy(createDependencies, 0, createEdges, createEdgesSize, createDependencies.length);
            createEdgesSize += createDependencies.length;
        }
        createOffsets[currentIndex + 1] = createEdgesSize;

        refreshOffsets[currentIndex] = refreshEdgesSize;
        if (refreshDependencies != null && refreshDependencies.length > 0) {
            System.arraycopy(refreshDependencies, 0, refreshEdges, refreshEdgesSize, refreshDependencies.length);
            refreshEdgesSize += refreshDependencies.length;
        }
        refreshOffsets[currentIndex + 1] = refreshEdgesSize;

        interceptorOffsets[currentIndex] = interceptorEdgesSize;
        if (interceptors != null && interceptors.length > 0) {
            for (int i = 0; i < interceptors.length; i++) {
                interceptorEdges[interceptorEdgesSize++] = interceptors[i].index;
            }
        }
        interceptorOffsets[currentIndex + 1] = interceptorEdgesSize;

        downstreamOffsets[currentIndex] = downstreamEdgesSize;
        if (downstreamConsumers != null && downstreamConsumers.length > 0) {
            System.arraycopy(downstreamConsumers, 0, downstreamEdges, downstreamEdgesSize, downstreamConsumers.length);
            downstreamEdgesSize += downstreamConsumers.length;
        }
        downstreamOffsets[currentIndex + 1] = downstreamEdgesSize;

        nodeTypes[currentIndex] = type;
        nodeTags[currentIndex] = tag;
        nodeConditions[currentIndex] = condition;

        var node = new NodeImpl<T>(this, currentIndex, factory);
        graphNodes[currentIndex] = node;
        size++;

        int[] existing = typeIndex.get(type);
        if (existing == null) {
            typeIndex.put(type, new int[] {currentIndex});
        } else {
            int[] updated = Arrays.copyOf(existing, existing.length + 1);
            updated[existing.length] = currentIndex;
            typeIndex.put(type, updated);
        }

        return node;
    }

    public InitializedGraph init() {
        var graph = new OptimizedGraphImpl(this);
        graph.init();
        return graph;
    }

    public List<Node<?>> getNodes() {
        return List.of(Arrays.copyOf(graphNodes, size));
    }

    @Nullable
    public Node<?> findNodeByType(Type type) {
        int[] nodes = typeIndex.get(type);
        if (nodes == null) {
            return null;
        }
        for (int i = 0; i < nodes.length; i++) {
            var graphNode = graphNodes[nodes[i]];
            if (graphNode.tag() == null) {
                return graphNode;
            }
        }
        return null;
    }

    public List<Node<?>> findNodesByType(Type type, @Nullable Class<?> tag) {
        int[] nodes = typeIndex.get(type);
        if (nodes == null) {
            return List.of();
        }

        var result = new ArrayList<Node<?>>(nodes.length);
        boolean isAnyTag = tag != null && tag == ANY_TAG_CLASS;
        for (int i = 0; i < nodes.length; i++) {
            var graphNode = graphNodes[nodes[i]];
            if (isAnyTag || Objects.equals(tag, graphNode.tag())) {
                result.add(graphNode);
            }
        }
        return result;
    }

    public <T> void replaceNode(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        int idx = casted.index;

        createOffsets[idx + 1] = createOffsets[idx];
        refreshOffsets[idx + 1] = refreshOffsets[idx];
        interceptorOffsets[idx + 1] = interceptorOffsets[idx];

        var newNode = new NodeImpl<T>(this, idx, factory);
        graphNodes[idx] = newNode;
    }

    public <T> void replaceNodeKeepDependencies(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        int idx = casted.index;

        var newNode = new NodeImpl<T>(this, idx, factory);
        graphNodes[idx] = newNode;
    }

    @SuppressWarnings("unchecked")
    public ApplicationGraphDraw copy() {
        var draw = new ApplicationGraphDraw(root, size, createEdgesSize, refreshEdgesSize, interceptorEdgesSize, downstreamEdgesSize);

        System.arraycopy(createOffsets, 0, draw.createOffsets, 0, size + 1);
        System.arraycopy(refreshOffsets, 0, draw.refreshOffsets, 0, size + 1);
        System.arraycopy(interceptorOffsets, 0, draw.interceptorOffsets, 0, size + 1);
        System.arraycopy(downstreamOffsets, 0, draw.downstreamOffsets, 0, size + 1); // Клонируем downstream оффсеты

        System.arraycopy(createEdges, 0, draw.createEdges, 0, createEdgesSize);
        System.arraycopy(refreshEdges, 0, draw.refreshEdges, 0, refreshEdgesSize);
        System.arraycopy(interceptorEdges, 0, draw.interceptorEdges, 0, interceptorEdgesSize);
        System.arraycopy(downstreamEdges, 0, draw.downstreamEdges, 0, downstreamEdgesSize); // Клонируем downstream ребра

        System.arraycopy(nodeTypes, 0, draw.nodeTypes, 0, size);
        System.arraycopy(nodeTags, 0, draw.nodeTags, 0, size);
        System.arraycopy(nodeConditions, 0, draw.nodeConditions, 0, size);

        draw.size = size;
        draw.createEdgesSize = createEdgesSize;
        draw.refreshEdgesSize = refreshEdgesSize;
        draw.interceptorEdgesSize = interceptorEdgesSize;
        draw.downstreamEdgesSize = downstreamEdgesSize;

        for (int i = 0; i < size; i++) {
            var node = graphNodes[i];
            var condition = nodeConditions[i];
            var replacedFactory = new ReplacedGraphFactory<>(draw.graphNodes, (NodeImpl<Object>) node);

            if (condition != null) {
                draw.nodeConditions[i] = graph -> condition.apply(ReplacedGraphFactory.replaced(draw.graphNodes, graph));
            }

            var replacedNode = new NodeImpl<>(draw, i, replacedFactory);
            draw.graphNodes[i] = replacedNode;

            int[] existing = draw.typeIndex.get(nodeTypes[i]);
            if (existing == null) {
                draw.typeIndex.put(nodeTypes[i], new int[] {i});
            } else {
                int[] updated = Arrays.copyOf(existing, existing.length + 1);
                updated[existing.length] = i;
                draw.typeIndex.put(nodeTypes[i], updated);
            }
        }
        return draw;
    }

    public ApplicationGraphDraw subgraph(List<Node<?>> excludeTransitive, Iterable<Node<?>> rootNodes) {
        int maxNodes = size;

        int[] seen = new int[maxNodes];
        Arrays.fill(seen, -1);

        var excludeTransitiveBits = new BitSet(maxNodes);
        for (int i = 0; i < excludeTransitive.size(); i++) {
            excludeTransitiveBits.set(((NodeImpl<?>) excludeTransitive.get(i)).index);
        }

        int[] stack = new int[maxNodes];
        int stackPtr = 0;
        byte[] state = new byte[maxNodes];

        for (var rootNode : rootNodes) {
            int rootIdx = ((NodeImpl<?>) rootNode).index;
            if (seen[rootIdx] == -1 && state[rootIdx] == 0) {
                stack[stackPtr++] = rootIdx;
            }
        }

        int subNodesCount = 0;
        int subCreateEdgesCount = 0;
        int subRefreshEdgesCount = 0;
        int subInterceptorEdgesCount = 0;

        var targetNodes = new int[maxNodes];

        while (stackPtr > 0) {
            int currentIdx = stack[stackPtr - 1];

            if (state[currentIdx] == 0) {
                state[currentIdx] = 1;

                int interStart = interceptorOffsets[currentIdx];
                int interEnd = interceptorOffsets[currentIdx + 1];
                for (int i = interEnd - 1; i >= interStart; i--) {
                    int interIdx = interceptorEdges[i];
                    if (seen[interIdx] == -1 && state[interIdx] == 0) {
                        stack[stackPtr++] = interIdx;
                    }
                }

                if (!excludeTransitiveBits.get(currentIdx)) {
                    int createStart = createOffsets[currentIdx];
                    int createEnd = createOffsets[currentIdx + 1];
                    for (int i = createEnd - 1; i >= createStart; i--) {
                        int depIdx = createEdges[i];
                        if (seen[depIdx] == -1 && state[depIdx] == 0) {
                            stack[stackPtr++] = depIdx;
                        }
                    }
                }
            } else if (state[currentIdx] == 1) {
                stackPtr--;
                state[currentIdx] = 2;

                if (seen[currentIdx] == -1) {
                    seen[currentIdx] = subNodesCount;
                    targetNodes[subNodesCount++] = currentIdx;

                    if (!excludeTransitiveBits.get(currentIdx)) {
                        subCreateEdgesCount += (createOffsets[currentIdx + 1] - createOffsets[currentIdx]);
                        subRefreshEdgesCount += (refreshOffsets[currentIdx + 1] - refreshOffsets[currentIdx]);
                    }
                    subInterceptorEdgesCount += (interceptorOffsets[currentIdx + 1] - interceptorOffsets[currentIdx]);
                }
            } else {
                stackPtr--;
            }
        }

        int subDownstreamEdgesCount = subCreateEdgesCount;

        var subgraph = new ApplicationGraphDraw(root, subNodesCount, subCreateEdgesCount, subRefreshEdgesCount, subInterceptorEdgesCount, subDownstreamEdgesCount);
        subgraph.size = subNodesCount;

        int[] subDownstreamCounts = new int[subNodesCount];

        for (int i = 0; i < subNodesCount; i++) {
            int oldIdx = targetNodes[i];

            subgraph.createOffsets[i] = subgraph.createEdgesSize;
            if (!excludeTransitiveBits.get(oldIdx)) {
                int start = createOffsets[oldIdx];
                int end = createOffsets[oldIdx + 1];
                for (int j = start; j < end; j++) {
                    int providerSubIdx = seen[createEdges[j]];
                    subgraph.createEdges[subgraph.createEdgesSize++] = providerSubIdx;
                    subDownstreamCounts[providerSubIdx]++;
                }
            }
            subgraph.createOffsets[i + 1] = subgraph.createEdgesSize;

            subgraph.refreshOffsets[i] = subgraph.refreshEdgesSize;
            if (!excludeTransitiveBits.get(oldIdx)) {
                int start = refreshOffsets[oldIdx];
                int end = refreshOffsets[oldIdx + 1];
                for (int j = start; j < end; j++) {
                    subgraph.refreshEdges[subgraph.refreshEdgesSize++] = seen[refreshEdges[j]];
                }
            }
            subgraph.refreshOffsets[i + 1] = subgraph.refreshEdgesSize;

            subgraph.interceptorOffsets[i] = subgraph.interceptorEdgesSize;
            int instart = interceptorOffsets[oldIdx];
            int inend = interceptorOffsets[oldIdx + 1];
            for (int j = instart; j < inend; j++) {
                subgraph.interceptorEdges[subgraph.interceptorEdgesSize++] = seen[interceptorEdges[j]];
            }
            subgraph.interceptorOffsets[i + 1] = subgraph.interceptorEdgesSize;

            subgraph.nodeTypes[i] = nodeTypes[oldIdx];
            subgraph.nodeTags[i] = nodeTags[oldIdx];

            var condition = nodeConditions[oldIdx];
            subgraph.nodeConditions[i] = condition == null ? null : new SubgraphIndexedCondition(condition, seen);

            var subFactory = new SubgraphIndexFactory<>(graphNodes[oldIdx].factory, seen);
            var subNode = new NodeImpl<>(subgraph, i, subFactory);
            subgraph.graphNodes[i] = subNode;

            int[] existing = subgraph.typeIndex.get(subgraph.nodeTypes[i]);
            if (existing == null) {
                subgraph.typeIndex.put(subgraph.nodeTypes[i], new int[] {i});
            } else {
                int[] updated = Arrays.copyOf(existing, existing.length + 1);
                updated[existing.length] = i;
                subgraph.typeIndex.put(subgraph.nodeTypes[i], updated);
            }
        }

        int currentDownstreamOffset = 0;
        for (int i = 0; i < subNodesCount; i++) {
            subgraph.downstreamOffsets[i] = currentDownstreamOffset;
            currentDownstreamOffset += subDownstreamCounts[i];
        }
        subgraph.downstreamOffsets[subNodesCount] = currentDownstreamOffset;

        System.arraycopy(subgraph.downstreamOffsets, 0, subDownstreamCounts, 0, subNodesCount);

        for (int consumerSubIdx = 0; consumerSubIdx < subNodesCount; consumerSubIdx++) {
            int start = subgraph.createOffsets[consumerSubIdx];
            int end = subgraph.createOffsets[consumerSubIdx + 1];
            for (int j = start; j < end; j++) {
                int providerSubIdx = subgraph.createEdges[j];
                int writePos = subDownstreamCounts[providerSubIdx]++;
                subgraph.downstreamEdges[writePos] = consumerSubIdx;
            }
        }
        subgraph.downstreamEdgesSize = subDownstreamEdgesCount;

        return subgraph;
    }

    public int[] getCreateEdges() {
        return createEdges;
    }

    public int[] getCreateOffsets() {
        return createOffsets;
    }

    public int[] getRefreshEdges() {
        return refreshEdges;
    }

    public int[] getRefreshOffsets() {
        return refreshOffsets;
    }

    public int[] getInterceptorEdges() {
        return interceptorEdges;
    }

    public int[] getInterceptorOffsets() {
        return interceptorOffsets;
    }

    public int[] getDownstreamEdges() {
        return downstreamEdges;
    }

    public int[] getDownstreamOffsets() {
        return downstreamOffsets;
    }

    public Type getNodeType(int index) {
        return nodeTypes[index];
    }

    @Nullable
    public Class<?> getNodeTag(int index) {
        return nodeTags[index];
    }

    @Nullable
    public Function<Graph, GraphCondition.ConditionResult> getNodeCondition(int index) {
        return nodeConditions[index];
    }

    public List<Node<?>> getNodeInterceptors(int nodeIndex) {
        int start = interceptorOffsets[nodeIndex];
        int end = interceptorOffsets[nodeIndex + 1];
        if (start == end) {
            return List.of();
        }
        var result = new ArrayList<Node<?>>(end - start);
        for (int i = start; i < end; i++) {
            result.add(graphNodes[interceptorEdges[i]]);
        }
        return result;
    }

    public List<Node<?>> getNodeCreateDependencies(int nodeIndex) {
        int start = createOffsets[nodeIndex];
        int end = createOffsets[nodeIndex + 1];
        if (start == end) {
            return List.of();
        }
        var result = new ArrayList<Node<?>>(end - start);
        for (int i = start; i < end; i++) {
            result.add(graphNodes[createEdges[i]]);
        }
        return result;
    }

    public List<Node<?>> getNodeRefreshDependencies(int nodeIndex) {
        int start = refreshOffsets[nodeIndex];
        int end = refreshOffsets[nodeIndex + 1];
        if (start == end) {
            return List.of();
        }
        var result = new ArrayList<Node<?>>(end - start);
        for (int i = start; i < end; i++) {
            result.add(graphNodes[refreshEdges[i]]);
        }
        return result;
    }

    public List<Node<?>> getNodeDownstreamConsumers(int nodeIndex) {
        int start = downstreamOffsets[nodeIndex];
        int end = downstreamOffsets[nodeIndex + 1];
        if (start == end) {
            return List.of();
        }
        var result = new ArrayList<Node<?>>(end - start);
        for (int i = start; i < end; i++) {
            result.add(graphNodes[downstreamEdges[i]]);
        }
        return result;
    }

    static class ReplacedGraphFactory<T> implements Graph.Factory<T> {
        final NodeImpl<?>[] nodes;
        final NodeImpl<T> node;

        public ReplacedGraphFactory(NodeImpl<?>[] nodes, NodeImpl<T> node) {
            this.nodes = nodes;
            this.node = node;
        }

        @Override
        public T get(RefreshableGraph graph) throws Exception {
            return node.factory.get(new ReplacedGraphProxy(graph, nodes));
        }

        static RefreshableGraph replaced(NodeImpl<?>[] nodes, Graph graph) {
            return new ReplacedGraphProxy(graph, nodes);
        }
    }

    private static final class ReplacedGraphProxy implements RefreshableGraph {
        private final Graph graph;
        private final NodeImpl<?>[] nodes;

        ReplacedGraphProxy(Graph graph, NodeImpl<?>[] nodes) {
            this.graph = graph;
            this.nodes = nodes;
        }

        @Override
        public void refresh(Node<?> fromNode) {
            var n = (NodeImpl<?>) fromNode;
            ((RefreshableGraph) graph).refresh(nodes[n.index]);
        }

        @Override
        public ApplicationGraphDraw draw() {
            return graph.draw();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> N get(Node<? extends N> node1) {
            var n = (NodeImpl<?>) node1;
            return graph.get((Node<N>) nodes[n.index]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> ValueOf<N> valueOf(Node<? extends N> node1) {
            var n = (NodeImpl<?>) node1;
            return graph.valueOf((Node<N>) nodes[n.index]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> PromiseOf<N> promiseOf(Node<? extends N> node1) {
            var n = (NodeImpl<?>) node1;
            return graph.promiseOf((Node<N>) nodes[n.index]);
        }

        @Override
        @SafeVarargs
        @SuppressWarnings("unchecked")
        public final <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... oneOfNodes) {
            var fixed = new NodeWithMapper[oneOfNodes.length];
            for (var i = 0; i < oneOfNodes.length; i++) {
                var n = (NodeImpl<?>) oneOfNodes[i].node();
                fixed[i] = new NodeWithMapper<>((Node<N>) nodes[n.index], oneOfNodes[i].mapper());
            }
            return graph.getOnePromiseOf(fixed);
        }
    }
}
