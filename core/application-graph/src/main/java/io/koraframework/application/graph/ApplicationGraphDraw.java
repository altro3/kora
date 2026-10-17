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

    public NodeImpl<?> getNodeByIndex(int index) {
        return graphNodes.get(index);
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
            graphNodes.size(),
            createDependencies,
            refreshDependencies,
            interceptors,
            factory
        );
        graphNodes.add(node);

        var existingList = typeIndex.get(type);
        if (existingList == null) {
            existingList = new ArrayList<>(2);
            typeIndex.put(type, existingList);
        }
        existingList.add(node);

        return node;
    }

    public InitializedGraph init() {
        var graph = new OptimizedGraphImpl(this);
        graph.init();
        return graph;
    }

    public List<Node<?>> getNodes() {
        return Collections.unmodifiableList(graphNodes);
    }

    public int size() {
        return graphNodes.size();
    }

    @Nullable
    public Node<?> findNodeByType(Type type) {
        var nodes = typeIndex.get(type);
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
        var nodes = typeIndex.get(type);
        if (nodes == null) {
            return List.of();
        }

        var result = new ArrayList<Node<?>>();
        boolean isAnyTag = tag != null && tag == ANY_TAG_CLASS;
        for (var i = 0; i < nodes.size(); i++) {
            var graphNode = nodes.get(i);
            if (isAnyTag || Objects.equals(tag, graphNode.tag())) {
                result.add(graphNode);
            }
        }
        return result;
    }

    public <T> void replaceNode(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        var newNode = new NodeImpl<T>(
            this, casted.type, casted.tag, casted.condition, casted.index, List.of(), List.of(), List.of(), factory
        );
        graphNodes.set(casted.index, newNode);
        var list = typeIndex.get(casted.type);
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
        graphNodes.set(casted.index, newNode);
        var list = typeIndex.get(casted.type);
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
        var draw = new ApplicationGraphDraw(root);
        var size = graphNodes.size();
        var newNodes = new NodeImpl<?>[size];

        for (var i = 0; i < size; i++) {
            var node = graphNodes.get(i);

            var createDepSize = node.createDependencies.size();
            var createDependencies = (List<Node<?>>) null;
            if (createDepSize == 0) {
                createDependencies = List.of();
            } else {
                createDependencies = new ArrayList<>(createDepSize);
                for (var j = 0; j < createDepSize; j++) {
                    if (node.createDependencies.get(j) instanceof NodeImpl<?> v) {
                        createDependencies.add(newNodes[v.index]);
                    }
                }
            }

            var refreshDepSize = node.refreshDependencies.size();
            var refreshDependencies = (List<Node<?>>) null;
            if (refreshDepSize == 0) {
                refreshDependencies = List.of();
            } else {
                refreshDependencies = new ArrayList<>(refreshDepSize);
                for (var j = 0; j < refreshDepSize; j++) {
                    if (node.refreshDependencies.get(j) instanceof NodeImpl<?> v) {
                        refreshDependencies.add(newNodes[v.index]);
                    }
                }
            }

            var interceptorsSize = node.interceptors.size();
            var interceptors = (List<Node<? extends GraphInterceptor<Object>>>) null;
            if (interceptorsSize == 0) {
                interceptors = List.of();
            } else {
                interceptors = new ArrayList<>(interceptorsSize);
                for (var j = 0; j < interceptorsSize; j++) {
                    var interceptor = node.interceptors.get(j);
                    interceptors.add((Node<? extends GraphInterceptor<Object>>) newNodes[((NodeImpl<?>) interceptor).index]);
                }
            }

            var condition = node.condition();
            var replacedFactory = new ReplacedGraphFactory<>(newNodes, (NodeImpl<Object>) node);

            var replacedNode = draw.addNode(
                node.type(),
                node.tag(),
                condition == null ? null : graph -> condition.apply(ReplacedGraphFactory.replaced(newNodes, graph)),
                createDependencies,
                refreshDependencies,
                interceptors,
                replacedFactory
            );
            newNodes[i] = (NodeImpl<?>) replacedNode;
        }
        return draw;
    }

    @SuppressWarnings("unchecked")
    public ApplicationGraphDraw subgraph(List<Node<?>> excludeTransitive, Iterable<Node<?>> rootNodes) {
        var maxNodes = graphNodes.size();

        int[] seen = new int[maxNodes];
        Arrays.fill(seen, -1);

        var excludeTransitiveBits = new BitSet(maxNodes);
        for (var i = 0; i < excludeTransitive.size(); i++) {
            excludeTransitiveBits.set(((NodeImpl<?>) excludeTransitive.get(i)).index);
        }

        var subgraph = new ApplicationGraphDraw(root);

        int[] stack = new int[maxNodes];
        int stackPtr = 0;

        byte[] state = new byte[maxNodes];

        for (var rootNode : rootNodes) {
            int rootIdx = ((NodeImpl<?>) rootNode).index;
            if (seen[rootIdx] == -1 && state[rootIdx] == 0) {
                stack[stackPtr++] = rootIdx;
            }
        }

        while (stackPtr > 0) {
            int currentIdx = stack[stackPtr - 1];
            var node = graphNodes.get(currentIdx);

            if (state[currentIdx] == 0) {
                state[currentIdx] = 1;

                for (int i = node.interceptors.size() - 1; i >= 0; i--) {
                    int interIdx = ((NodeImpl<?>) node.interceptors.get(i)).index;
                    if (seen[interIdx] == -1 && state[interIdx] == 0) {
                        stack[stackPtr++] = interIdx;
                    }
                }

                if (!excludeTransitiveBits.get(currentIdx)) {
                    for (int i = node.createDependencies.size() - 1; i >= 0; i--) {
                        if (node.createDependencies.get(i) instanceof NodeImpl<?> v) {
                            if (seen[v.index] == -1 && state[v.index] == 0) {
                                stack[stackPtr++] = v.index;
                            }
                        }
                    }
                }
            } else if (state[currentIdx] == 1) {
                stackPtr--;
                state[currentIdx] = 2;

                if (seen[currentIdx] == -1) {
                    var createDepSize = node.createDependencies.size();
                    List<Node<?>> dependencyNodes;
                    if (createDepSize == 0 || excludeTransitiveBits.get(currentIdx)) {
                        dependencyNodes = List.of();
                    } else {
                        dependencyNodes = new ArrayList<>(createDepSize);
                        for (var j = 0; j < createDepSize; j++) {
                            if (node.createDependencies.get(j) instanceof NodeImpl<?> v) {
                                dependencyNodes.add(subgraph.graphNodes.get(seen[v.index]));
                            }
                        }
                    }

                    var interceptorsSize = node.interceptors.size();
                    List<Node<? extends GraphInterceptor<Object>>> interceptors;
                    if (interceptorsSize == 0) {
                        interceptors = List.of();
                    } else {
                        interceptors = new ArrayList<>(interceptorsSize);
                        for (var j = 0; j < interceptorsSize; j++) {
                            int interIdx = ((NodeImpl<?>) node.interceptors.get(j)).index;
                            interceptors.add((Node<? extends GraphInterceptor<Object>>) subgraph.graphNodes.get(seen[interIdx]));
                        }
                    }

                    Graph.Factory<Object> factory = graph -> ((NodeImpl<Object>) node).factory.get(new RemappedGraphProxy(graph, subgraph, seen));
                    var condition = node.condition();

                    var newNode = (NodeImpl<?>) subgraph.addNode(
                        node.type(), node.tag(),
                        condition == null ? null : graph -> condition.apply(new RemappedGraphProxy(graph, subgraph, seen)),
                        dependencyNodes, dependencyNodes, interceptors, factory
                    );
                    seen[currentIdx] = newNode.index;
                }
            } else {
                stackPtr--;
            }
        }
        return subgraph;
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

    private static final class RemappedGraphProxy implements RefreshableGraph {
        private final Graph graph;
        private final ApplicationGraphDraw subgraph;
        private final int[] seen;

        RemappedGraphProxy(Graph graph, ApplicationGraphDraw subgraph, int[] seen) {
            this.graph = graph;
            this.subgraph = subgraph;
            this.seen = seen;
        }

        @Override
        public void refresh(Node<?> fromNode) {
            var casted = (NodeImpl<?>) fromNode;
            var realNode = subgraph.getNodeByIndex(seen[casted.index]);
            ((RefreshableGraph) graph).refresh(realNode);
        }

        @Override
        public ApplicationGraphDraw draw() {
            return subgraph;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q> Q get(Node<? extends Q> node1) {
            var casted = (NodeImpl<?>) node1;
            var realNode = (Node<Q>) subgraph.getNodeByIndex(seen[casted.index]);
            return graph.get(realNode);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q> ValueOf<Q> valueOf(Node<? extends Q> node1) {
            var casted = (NodeImpl<?>) node1;
            var realNode = (Node<Q>) subgraph.getNodeByIndex(seen[casted.index]);
            return graph.valueOf(realNode);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q> PromiseOf<Q> promiseOf(Node<? extends Q> node1) {
            var casted = (NodeImpl<?>) node1;
            var realNode = (Node<? extends Q>) subgraph.getNodeByIndex(seen[casted.index]);
            return graph.promiseOf(realNode);
        }

        @Override
        @SafeVarargs
        @SuppressWarnings("unchecked")
        public final <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... oneOfNodes) {
            var fixed = new NodeWithMapper[oneOfNodes.length];
            for (var i = 0; i < oneOfNodes.length; i++) {
                var n = (NodeImpl<?>) oneOfNodes[i].node();
                fixed[i] = new NodeWithMapper<>((Node<N>) subgraph.getNodeByIndex(seen[n.index]), oneOfNodes[i].mapper());
            }
            return graph.getOnePromiseOf(fixed);
        }
    }
}
