package io.koraframework.application.graph;

import io.koraframework.application.graph.exception.MoreThanOneConditionalNodeMatches;
import io.koraframework.application.graph.exception.NoneOfConditionalNodeMatches;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;

public interface Graph {
    ApplicationGraphDraw draw();

    <T> T get(Node<? extends T> node);

    /**
     * <b>Русский</b>: Возвращает значение узла либо {@code null}, если узел условный и его условие не выполнено.
     * Используется для {@code @Nullable} зависимостей на {@link io.koraframework.common.annotation.Conditional} компоненты.
     * <hr>
     * <b>English</b>: Returns the node value, or {@code null} when the node is conditional and its condition failed.
     * Used for {@code @Nullable} dependencies on {@link io.koraframework.common.annotation.Conditional} components.
     */
    default <T> @Nullable T getNullable(Node<? extends T> node) {
        var condition = node.condition();
        if (condition != null && condition.apply(this) instanceof GraphCondition.ConditionResult.Failed) {
            return null;
        }
        return this.get(node);
    }

    <T> ValueOf<T> valueOf(Node<? extends T> node);

    <T> PromiseOf<T> promiseOf(Node<? extends T> node);

    @SuppressWarnings("unchecked")
    default <N, V> V getOneOf(NodeWithMapper<N, V>... nodes) {
        var node = getOneNodeMatchingCondition(this, nodes);
        var value = this.get(node.node());
        return node.mapper().apply(value);
    }

    @SuppressWarnings("unchecked")
    default <N, V> ValueOf<V> getOneValueOf(NodeWithMapper<N, V>... nodes) {
        return () -> {
            var node = getOneNodeMatchingCondition(this, nodes);
            var value = this.get(node.node());
            return node.mapper().apply(value);
        };
    }

    @SuppressWarnings("unchecked")
    <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... nodes);

    default GraphCondition condition(Node<? extends GraphCondition> node) {
        return get(node);
    }

    interface Factory<T> {
        T get(RefreshableGraph graph) throws Exception;
    }

    @SafeVarargs
    private static <T, V> NodeWithMapper<T, V> getOneNodeMatchingCondition(Graph graph, NodeWithMapper<T, V>... nodes) {
        NodeWithMapper<T, V> firstMatched = null;
        int matchCount = 0;

        for (var node : nodes) {
            var condition = node.node().condition();
            if (condition == null) {
                matchCount++;
                if (matchCount == 1) {
                    firstMatched = node;
                }
            } else {
                if (condition.apply(graph) instanceof GraphCondition.ConditionResult.Matched) {
                    matchCount++;
                    if (matchCount == 1) {
                        firstMatched = node;
                    }
                }
            }
        }

        if (matchCount == 1) {
            return firstMatched;
        }

        var errors = new ArrayList<Map.Entry<Node<?>, GraphCondition.ConditionResult.Failed>>(nodes.length);
        var matchReasons = new ArrayList<Map.Entry<Node<?>, GraphCondition.ConditionResult.Matched>>(nodes.length);
        for (var node : nodes) {
            var condition = node.node().condition();
            if (condition == null) {
                matchReasons.add(Map.entry(node.node(), new GraphCondition.ConditionResult.Matched("Node %s has no conditions".formatted(node.toString()))));
            } else {
                switch (condition.apply(graph)) {
                    case GraphCondition.ConditionResult.Failed failed -> errors.add(Map.entry(node.node(), failed));
                    case GraphCondition.ConditionResult.Matched matched -> matchReasons.add(Map.entry(node.node(), matched));
                }
            }
        }

        if (matchCount == 0) {
            throw new NoneOfConditionalNodeMatches(errors);
        } else {
            throw new MoreThanOneConditionalNodeMatches(matchReasons);
        }
    }
}
