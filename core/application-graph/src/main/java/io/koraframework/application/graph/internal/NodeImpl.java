package io.koraframework.application.graph.internal;

import io.koraframework.application.graph.*;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.function.Function;

public final class NodeImpl<T> implements Node<T> {
    public final ApplicationGraphDraw graphDraw;
    public final int index;
    public final Graph.Factory<? extends T> factory;

    public final Type type;
    @Nullable
    public final Class<?> tag;
    @Nullable
    public final Function<Graph, GraphCondition.ConditionResult> condition;

    public NodeImpl(
        ApplicationGraphDraw graphDraw,
        Type type,
        @Nullable Class<?> tag,
        @Nullable Function<Graph, GraphCondition.ConditionResult> condition,
        int index,
        Graph.Factory<? extends T> factory) {
        this.graphDraw = graphDraw;
        this.index = index;
        this.factory = factory;
        this.type = type;
        this.tag = tag;
        this.condition = condition;
    }

    @Override
    public Type type() {
        return this.type;
    }

    @Nullable
    @Override
    public Class<?> tag() {
        return this.tag;
    }

    @Nullable
    @Override
    public Function<Graph, GraphCondition.ConditionResult> condition() {
        return this.condition;
    }

    @Override
    public String toString() {
        int depSize = graphDraw.getCreateDependenciesCount(this.index);
        if (tag == null) {
            return "[#%s] %s (%s dependencies)".formatted(index, type, depSize);
        } else {
            return "[#%s] %s (@Tag(%s)) (%s dependencies)".formatted(index, type, tag, depSize);
        }
    }
}
