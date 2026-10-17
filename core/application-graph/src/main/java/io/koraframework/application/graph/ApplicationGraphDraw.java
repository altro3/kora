package io.koraframework.application.graph;

import io.koraframework.application.graph.internal.*;
import org.jspecify.annotations.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

public class ApplicationGraphDraw {

    private static final String ANY_TAG_NAME = "io.koraframework.common.Tag.Any";
    private static final NodeImpl<?>[] EMPTY_NODE_ARRAY = new NodeImpl<?>[0];
    private static final @Nullable Class<?> ANY_TAG_CLASS;

    static {
        Class<?> clazz = null;
        try {
            clazz = Class.forName(ANY_TAG_NAME);
        } catch (ClassNotFoundException _) {
        }
        ANY_TAG_CLASS = clazz;
    }

    private final List<NodeImpl<?>> graphNodes = new ArrayList<>();
    private final Class<?> root;
    private final Map<Type, List<NodeImpl<?>>> typeIndex = new HashMap<>();

    public ApplicationGraphDraw(Class<?> root) {
        this.root = root;
    }

    public Class<?> getRoot() {
        return root;
    }

    public <T> Node<T> addNode(
        Type type,
        @Nullable Class<?> tag,
        @Nullable Function<Graph, GraphCondition.ConditionResult> condition,
        List<Node<?>> createDependencies,
        List<Node<?>> refreshDependencies,
        List<Node<? extends GraphInterceptor<T>>> interceptors,
        Graph.Factory<? extends T> factory) {
        for (var i = 0; i < createDependencies.size(); i++) {
            if (createDependencies.get(i) instanceof NodeImpl<?> node) {
                if (node.index >= 0 && node.graphDraw != this) {
                    throw new IllegalArgumentException("Graph dependency belongs to another application graph: node index %s, type %s".formatted(node.index, node.type));
                }
            }
        }

        var node = new NodeImpl<>(
            this,
            type,
            tag,
            condition,
            this.graphNodes.size(),
            createDependencies,
            refreshDependencies,
            interceptors,
            factory
        );
        this.graphNodes.add(node);

        var existingList = this.typeIndex.get(type);
        if (existingList == null) {
            existingList = new ArrayList<>(2);
            this.typeIndex.put(type, existingList);
        }
        existingList.add(node);

        return node;
    }

    public InitializedGraph init() {
        var graph = new GraphImpl(this);
        graph.init();
        return graph;
    }

    public List<Node<?>> getNodes() {
        return Collections.unmodifiableList(this.graphNodes);
    }

    public int size() {
        return this.graphNodes.size();
    }

    @Nullable
    public Node<?> findNodeByType(Type type) {
        var nodes = this.typeIndex.get(type);
        if (nodes == null) {
            return null;
        }
        for (var i = 0; i < nodes.size(); i++) {
            var graphNode = nodes.get(i);
            if (graphNode.tag() == null) {
                return graphNode;
            }
        }
        return null;
    }

    public List<Node<?>> findNodesByType(Type type, @Nullable Class<?> tag) {
        var nodes = this.typeIndex.get(type);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        boolean isAnyTag = tag != null && (tag == ANY_TAG_CLASS || ANY_TAG_NAME.equals(tag.getName()));
        if (isAnyTag) {
            return new ArrayList<>(nodes);
        }

        var result = (ArrayList<Node<?>>) null;
        for (var i = 0; i < nodes.size(); i++) {
            var graphNode = nodes.get(i);
            if (tag == graphNode.tag()) {
                if (result == null) {
                    result = new ArrayList<>(2);
                }
                result.add(graphNode);
            }
        }

        return result == null ? List.of() : result;
    }

    public <T> void replaceNode(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        var newNode = new NodeImpl<T>(
            this, casted.type, casted.tag, casted.condition, casted.index, List.of(), List.of(), List.of(), factory
        );
        this.graphNodes.set(casted.index, newNode);

        var list = this.typeIndex.get(casted.type);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).index == casted.index) {
                    list.set(i, newNode);
                    break;
                }
            }
        }
    }

    public <T> void replaceNodeKeepDependencies(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        var newNode = new NodeImpl<T>(
            this, casted.type, casted.tag, casted.condition, casted.index, casted.createDependencies, casted.refreshDependencies, List.of(), factory
        );
        this.graphNodes.set(casted.index, newNode);

        var list = this.typeIndex.get(casted.type);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).index == casted.index) {
                    list.set(i, newNode);
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public ApplicationGraphDraw copy() {
        var draw = new ApplicationGraphDraw(this.root);
        var size = this.graphNodes.size();
        var newNodes = new NodeImpl<?>[size];

        for (var i = 0; i < size; i++) {
            var node = this.graphNodes.get(i);
            var createDependencies = new ArrayList<Node<?>>(node.createDependencies.size());
            for (var j = 0; j < node.createDependencies.size(); j++) {
                if (node.createDependencies.get(j) instanceof NodeImpl<?> v) {
                    createDependencies.add(newNodes[v.index]);
                }
            }
            var refreshDependencies = new ArrayList<Node<?>>(node.refreshDependencies.size());
            for (var j = 0; j < node.refreshDependencies.size(); j++) {
                if (node.refreshDependencies.get(j) instanceof NodeImpl<?> v) {
                    refreshDependencies.add(newNodes[v.index]);
                }
            }
            var interceptors = new ArrayList<Node<? extends GraphInterceptor<Object>>>(node.interceptors.size());
            for (var j = 0; j < node.interceptors.size(); j++) {
                var interceptor = node.interceptors.get(j);
                interceptors.add((Node<? extends GraphInterceptor<Object>>) newNodes[((NodeImpl<?>) interceptor).index]);
            }

            var condition = node.condition();
            var replacedNode = draw.addNode(
                node.type(),
                node.tag(),
                condition == null ? null : graph -> condition.apply(ReplacedGraphFactory.replaced(newNodes, graph)),
                createDependencies,
                refreshDependencies,
                interceptors,
                new ReplacedGraphFactory<>(newNodes, (NodeImpl<Object>) node)
            );
            newNodes[i] = (NodeImpl<?>) replacedNode;
        }
        return draw;
    }

    public ApplicationGraphDraw subgraph(List<Node<?>> excludeTransitive, Iterable<Node<?>> rootNodes) {
        var maxNodes = this.graphNodes.size();
        var seen = new int[maxNodes];
        Arrays.fill(seen, -1);

        var excludeTransitiveBits = new BitSet(maxNodes);
        for (var i = 0; i < excludeTransitive.size(); i++) {
            excludeTransitiveBits.set(((NodeImpl<?>) excludeTransitive.get(i)).index);
        }

        var subgraph = new ApplicationGraphDraw(this.root);
        for (var rootNode : rootNodes) {
            var casted = (NodeImpl<?>) rootNode;
            subgraphAccept(this.graphNodes.get(casted.index), seen, excludeTransitiveBits, subgraph);
        }
        return subgraph;
    }

    @SuppressWarnings("unchecked")
    private <T> Node<T> subgraphAccept(NodeImpl<T> node, int[] seen, BitSet excludeTransitiveBits, ApplicationGraphDraw subgraph) {
        var nodeIndex = node.index;
        if (seen[nodeIndex] == -1) {
            var dependencyNodes = new ArrayList<Node<?>>();
            var interceptors = new ArrayList<Node<? extends GraphInterceptor<T>>>();
            if (!excludeTransitiveBits.get(nodeIndex)) {
                for (var j = 0; j < node.createDependencies.size(); j++) {
                    if (node.createDependencies.get(j) instanceof NodeImpl<?> v) {
                        dependencyNodes.add(subgraphAccept(v, seen, excludeTransitiveBits, subgraph));
                    }
                }
            }
            for (var j = 0; j < node.interceptors.size(); j++) {
                interceptors.add(subgraphAccept((NodeImpl<? extends GraphInterceptor<T>>) node.interceptors.get(j), seen, excludeTransitiveBits, subgraph));
            }
            int[] seenSnapshot = seen.clone();
            Graph.Factory<T> factory = graph -> node.factory.get(new RemappedGraphProxy(graph, subgraph, seenSnapshot));
            var condition = node.condition();
            var newNode = (NodeImpl<T>) subgraph.addNode(
                node.type(), node.tag(),
                condition == null ? null : graph -> condition.apply(new RemappedGraphProxy(graph, subgraph, seenSnapshot)),
                dependencyNodes, dependencyNodes, interceptors, factory
            );
            seen[nodeIndex] = newNode.index;
            return newNode;
        }
        return (Node<T>) subgraph.graphNodes.get(seen[nodeIndex]);
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
            return node.factory.get(new ReplacedGraphProxy(graph, this.nodes));
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
            ((RefreshableGraph) graph).refresh(this.nodes[n.index]);
        }

        @Override
        public ApplicationGraphDraw draw() {
            return graph.draw();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> N get(Node<? extends N> node1) {
            var n = (NodeImpl<?>) node1;
            return graph.get((Node<N>) this.nodes[n.index]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> ValueOf<N> valueOf(Node<? extends N> node1) {
            var n = (NodeImpl<?>) node1;
            return graph.valueOf((Node<N>) this.nodes[n.index]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> PromiseOf<N> promiseOf(Node<? extends N> node1) {
            var n = (NodeImpl<?>) node1;
            return graph.promiseOf((Node<N>) this.nodes[n.index]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... oneOfNodes) {
            var fixed = new NodeWithMapper[oneOfNodes.length];
            for (var i = 0; i < oneOfNodes.length; i++) {
                var n = (NodeImpl<?>) oneOfNodes[i].node();
                fixed[i] = new NodeWithMapper<>((Node<N>) this.nodes[n.index], oneOfNodes[i].mapper());
            }
            return graph.getOnePromiseOf(fixed);
        }
    }

    private static final class RemappedGraphProxy implements RefreshableGraph {
        private final Graph graph;
        private final ApplicationGraphDraw subgraph;
        private final int[] seen;
        private final NodeImpl<?>[] subgraphNodes;

        RemappedGraphProxy(Graph graph, ApplicationGraphDraw subgraph, int[] seen) {
            this.graph = graph;
            this.subgraph = subgraph;
            this.seen = seen;
            this.subgraphNodes = subgraph.getNodes().toArray(EMPTY_NODE_ARRAY);
        }

        @Override
        public void refresh(Node<?> fromNode) {
            var casted = (NodeImpl<?>) fromNode;
            var realNode = this.subgraphNodes[this.seen[casted.index]];
            ((RefreshableGraph) graph).refresh(realNode);
        }

        @Override
        public ApplicationGraphDraw draw() {
            return this.subgraph;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q> Q get(Node<? extends Q> node1) {
            var casted = (NodeImpl<?>) node1;
            var realNode = (Node<Q>) this.subgraphNodes[this.seen[casted.index]];
            return graph.get(realNode);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q> ValueOf<Q> valueOf(Node<? extends Q> node1) {
            var casted = (NodeImpl<?>) node1;
            var realNode = (Node<Q>) this.subgraphNodes[this.seen[casted.index]];
            return graph.valueOf(realNode);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q> PromiseOf<Q> promiseOf(Node<? extends Q> node1) {
            var casted = (NodeImpl<?>) node1;
            var realNode = (Node<? extends Q>) this.subgraphNodes[this.seen[casted.index]];
            return graph.promiseOf(realNode);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... oneOfNodes) {
            var fixed = new NodeWithMapper[oneOfNodes.length];
            for (var i = 0; i < oneOfNodes.length; i++) {
                var n = (NodeImpl<?>) oneOfNodes[i].node();
                fixed[i] = new NodeWithMapper<>((Node<N>) this.subgraphNodes[this.seen[n.index]], oneOfNodes[i].mapper());
            }
            return graph.getOnePromiseOf(fixed);
        }
    }
}
