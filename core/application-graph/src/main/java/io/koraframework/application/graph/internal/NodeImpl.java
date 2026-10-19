package io.koraframework.application.graph.internal;

import io.koraframework.application.graph.*;
import org.jspecify.annotations.Nullable;
import java.lang.reflect.Type;
import java.util.function.Function;

public final class NodeImpl<T> implements Node<T> {
    public final ApplicationGraphDraw graphDraw;
    public final int index;
    public final Graph.Factory<? extends T> factory;

    public NodeImpl(ApplicationGraphDraw graphDraw, int index, Graph.Factory<? extends T> factory) {
        this.graphDraw = graphDraw;
        this.index = index;
        this.factory = factory;
    }

    @Override
    public Type type() {
        return graphDraw.getNodeType(index);
    }

    @Nullable
    @Override
    public Class<?> tag() {
        return graphDraw.getNodeTag(index);
    }

    @Nullable
    @Override
    public Function<Graph, GraphCondition.ConditionResult> condition() {
        return graphDraw.getNodeCondition(index);
    }

    @Override
    public String toString() {
        int depSize = graphDraw.getCreateDependenciesCount(index);
        Type type = type();
        Class<?> tag = tag();
        if (tag == null) {
            return "[#%s] %s (%s dependencies)".formatted(index, type, depSize);
        } else {
            return "[#%s] %s (@Tag(%s)) (%s dependencies)".formatted(index, type, tag, depSize);
        }
    }
}
