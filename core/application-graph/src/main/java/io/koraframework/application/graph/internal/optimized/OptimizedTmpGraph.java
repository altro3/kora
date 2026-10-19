package io.koraframework.application.graph.internal.optimized;

import io.koraframework.application.graph.*;
import io.koraframework.application.graph.GraphCondition.*;
import io.koraframework.application.graph.internal.*;
import io.koraframework.application.graph.internal.optimized.OptimizedGraphImpl.*;
import org.jspecify.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class OptimizedTmpGraph implements RefreshableGraph {

    private final OptimizedGraphImpl rootGraph;
    private final Object[] tmpArray;
    private final Object[] rootRawObjects;
    private final AtomicReferenceArray<@Nullable ConditionResult> conditionCache;
    private final AtomicReferenceArray<@Nullable TmpValueOf<?>> valueOfs;
    private final AtomicReferenceArray<@Nullable BasePromiseOf<?>> promises;
    private final byte[] changedNodes;
    private final byte[] localRefreshListeners;
    private volatile @Nullable OptimizedGraphImpl delegate;

    public OptimizedTmpGraph(OptimizedGraphImpl rootGraph) {
        this.rootGraph = rootGraph;
        this.rootRawObjects = rootGraph.graphObjects;
        var length = this.rootRawObjects.length;
        this.tmpArray = new Object[length];
        System.arraycopy(this.rootRawObjects, 0, this.tmpArray, 0, length);
        this.valueOfs = new AtomicReferenceArray<>(length);
        this.promises = new AtomicReferenceArray<>(length);
        this.conditionCache = new AtomicReferenceArray<>(length);
        this.changedNodes = new byte[length];
        this.localRefreshListeners = new byte[length];
    }

    @Override
    public ApplicationGraphDraw draw() {
        return rootGraph.draw();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Node<? extends T> node) {
        var del = this.delegate;
        if (del != null) {
            return del.get(node);
        }
        var index = ((NodeImpl<?>) node).index;
        var val = tmpArray[index];
        if (val == null) {
            throw new IllegalStateException("Node not initialized: " + index);
        }
        if (val instanceof OptimizedGraphImpl.ConditionFailedValue failedValue) {
            throw new IllegalStateException("Graph node value was not initialized because condition failed: " + failedValue.reason);
        }
        return (T) val;
    }

    @Override
    public GraphCondition condition(Node<? extends GraphCondition> node) {
        var del = this.delegate;
        if (del != null) {
            return del.condition(node);
        }
        var index = ((NodeImpl<?>) node).index;
        return () -> {
            var cached = conditionCache.get(index);
            if (cached != null) {
                return cached;
            }
            var rootCache = rootGraph.getConditionResultsCache().get(index);
            if (rootCache != null && changedNodes[index] == 0) {
                return rootCache;
            }
            var computed = get(node).eval();
            if (conditionCache.compareAndSet(index, null, computed)) {
                return computed;
            }
            return Objects.requireNonNull(conditionCache.get(index));
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ValueOf<T> valueOf(Node<? extends T> node) {
        var casted = (NodeImpl<T>) node;
        var fixed = (NodeImpl<T>) rootGraph.draw().getNodeByIndex(casted.index);
        var value = new TmpValueOf<>(fixed, this);
        valueOfs.set(casted.index, value);
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> PromiseOf<T> promiseOf(Node<? extends T> node) {
        var casted = (NodeImpl<T>) node;
        var fixed = (NodeImpl<T>) rootGraph.draw().getNodeByIndex(casted.index);
        var promise = new PromiseOfImpl<>(fixed);
        promises.set(casted.index, promise);
        return promise;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... oneOfNodes) {
        var fixedNodes = new NodeWithMapper[oneOfNodes.length];
        var primaryIndex = -1;

        for (var i = 0; i < oneOfNodes.length; i++) {
            var node = oneOfNodes[i];
            var casted = (NodeImpl<N>) node.node();
            if (i == 0) {
                primaryIndex = casted.index;
            }
            var fixed = (NodeImpl<N>) rootGraph.draw().getNodeByIndex(casted.index);
            fixedNodes[i] = new NodeWithMapper<>(fixed, node.mapper());
        }

        var promise = new PromiseOfOneConditionalImpl<N, V>(fixedNodes);
        if (primaryIndex >= 0) {
            promises.set(primaryIndex, promise);
        }
        return promise;
    }

    @SuppressWarnings("unchecked")
    public <T> void createNode(NodeImpl<T> node, int startFrom, boolean isTrace) throws Exception {
        var oldObject = (T) rootRawObjects[node.index];
        var draw = rootGraph.draw();

        if (oldObject != null && node.index != startFrom) {
            var dependencyChanged = false;
            int[] rEdges = draw.getRefreshEdges();
            int[] rOffsets = draw.getRefreshOffsets();
            int start = rOffsets[node.index], end = rOffsets[node.index + 1];
            for (int i = start; i < end; i++) {
                if (changedNodes[rEdges[i]] == 1) {
                    dependencyChanged = true;
                    break;
                }
            }
            if (!dependencyChanged) {
                int[] iEdges = draw.getInterceptorEdges();
                int[] iOffsets = draw.getInterceptorOffsets();
                start = iOffsets[node.index];
                end = iOffsets[node.index + 1];
                for (int i = start; i < end; i++) {
                    if (changedNodes[iEdges[i]] == 1) {
                        dependencyChanged = true;
                        break;
                    }
                }
            }
            if (!dependencyChanged) return;
        }

        var conditionFunction = draw.getNodeCondition(node.index);
        if (conditionFunction != null) {
            if (conditionFunction.apply(this) instanceof ConditionResult.Failed failed) {
                if (isTrace) {
                    rootGraph.logger.trace("Creating node {} canceled: {}", node.index, failed.reason());
                }
                tmpArray[node.index] = new ConditionFailedValue(failed.reason());
                changedNodes[node.index] = 1;
                return;
            }
        }

        if (isTrace) {
            int[] cEdges = draw.getCreateEdges();
            int[] cOffsets = draw.getCreateOffsets();
            int start = cOffsets[node.index], end = cOffsets[node.index + 1];
            var sb = new StringBuilder("[");
            for (int i = start; i < end; i++) {
                if (i > start) sb.append(",");
                sb.append(cEdges[i]);
            }
            sb.append("]");
            rootGraph.logger.trace("Creating node {}, dependencies {}", node.index, sb);
        }

        var newObject = Objects.requireNonNull(node.factory.get(this));

        var isUnchanged = false;
        if (oldObject != null) {
            try {
                isUnchanged = (newObject == oldObject || newObject.equals(oldObject));
            } catch (Throwable _) {}
        }

        if (isUnchanged) {
            if (newObject instanceof Lifecycle lifecycle) {
                lifecycle.release();
            } else if (newObject instanceof Closeable closeable) {
                closeable.close();
            }
            return;
        }
        tmpArray[node.index] = newObject;
        changedNodes[node.index] = 1;

        if (newObject instanceof RefreshListener) {
            localRefreshListeners[node.index] = 1;
        }

        if (isTrace) {
            rootGraph.logger.trace("Created node {} {}", node.index, newObject.getClass());
        }

        if (newObject instanceof Lifecycle lifecycle) {
            if (isTrace) {
                rootGraph.logger.trace("Initializing node {} of class {}", node.index, lifecycle.getClass());
            }
            try {
                lifecycle.init();
                if (isTrace) {
                    rootGraph.logger.trace("Node Initializing {} of class {} complete", node.index, lifecycle.getClass());
                }
            } catch (CancellationException e) {
                if (isTrace) {
                    rootGraph.logger.trace("Node Initializing {} of class {} cancelled", node.index, lifecycle.getClass());
                }
                throw e;
            } catch (CompletionException ce) {
                if (isTrace) {
                    rootGraph.logger.trace("Node Initializing {} of class {} error", node.index, lifecycle.getClass(), ce.getCause());
                }
                throw ce;
            } catch (RuntimeException | Error e) {
                if (isTrace) {
                    rootGraph.logger.trace("Node Initializing {} of class {} error", node.index, lifecycle.getClass(), e);
                }
                throw e;
            } catch (Throwable e) {
                if (isTrace) {
                    rootGraph.logger.trace("Initializing node {} of class {} error", node.index, lifecycle.getClass(), e);
                }
                throw new IllegalStateException("Lifecycle init failed with checked exception for node %s at index %s".formatted(node.type(), node.index), e);
            }
        }

        int[] iEdges = draw.getInterceptorEdges();
        int[] iOffsets = draw.getInterceptorOffsets();
        int interStart = iOffsets[node.index], interEnd = iOffsets[node.index + 1];

        if (interStart != interEnd) {
            for (int i = interStart; i < interEnd; i++) {
                int interceptorIndex = iEdges[i];
                var interceptorObject = (GraphInterceptor<T>) tmpArray[interceptorIndex];

                if (isTrace) {
                    rootGraph.logger.trace("Intercepting init node {} of class {} with node {} of class {}", node.index, newObject.getClass(), interceptorIndex, interceptorObject.getClass());
                }
                try {
                    var intercepted = interceptorObject.afterInit(newObject);
                    if (isTrace) {
                        rootGraph.logger.trace("Intercepting init node {} of class {} with node {} of class {} complete", node.index, newObject.getClass(), interceptorIndex, interceptorObject.getClass());
                    }
                    newObject = intercepted;
                } catch (RuntimeException | Error e) {
                    if (isTrace) {
                        rootGraph.logger.trace("Intercepting init node {} of class {} with node {} of class {} error", node.index, newObject.getClass(), interceptorIndex, interceptorObject.getClass(), e);
                    }
                    throw e;
                } catch (Throwable e) {
                    if (isTrace) {
                        rootGraph.logger.trace("Intercepting init node {} of class {} with node {} of class {} error", node.index, newObject.getClass(), interceptorIndex, interceptorObject.getClass(), e);
                    }
                    throw new IllegalStateException("Graph interceptor failed with checked exception for node %s".formatted(node.type()), e);
                }
            }
        }
        tmpArray[node.index] = newObject;
    }

    public void commit(int startFrom) {
        var length = changedNodes.length;
        var oldValuesToRelease = new Object[length];

        for (var i = 0; i < length; i++) {
            if (changedNodes[i] == 1) {
                var newVal = tmpArray[i];
                if (newVal != null) {
                    oldValuesToRelease[i] = rootRawObjects[i];
                    rootRawObjects[i] = newVal;
                }
            }
        }

        var rootListeners = rootGraph.getRefreshListenerIndices();
        for (var i = 0; i < length; i++) {
            if (changedNodes[i] == 1) {
                if (localRefreshListeners[i] == 1) {
                    rootListeners.set(i);
                } else {
                    rootListeners.clear(i);
                }
            }
        }

        for (var i = 0; i < length; i++) {
            var v = valueOfs.get(i);
            if (v != null) {
                v.tmpGraph = rootGraph;
            }
        }

        for (var i = 0; i < length; i++) {
            var p = promises.get(i);
            if (p != null) {
                p.graph = rootGraph;
            }
        }

        var cache = rootGraph.getConditionResultsCache();
        var condCacheLength = conditionCache.length();
        for (var i = 0; i < condCacheLength; i++) {
            var cond = conditionCache.get(i);
            if (cond != null) {
                cache.set(i, cond);
            }
        }

        if (startFrom > 0) {
            var draw = rootGraph.draw();
            int[] iEdges = draw.getInterceptorEdges();
            int[] iOffsets = draw.getInterceptorOffsets();

            for (var i = length - 1; i >= 0; i--) {
                var oldVal = oldValuesToRelease[i];
                if (oldVal == null) continue;

                try {
                    var interStart = iOffsets[i];
                    var interEnd = iOffsets[i + 1];

                    for (var j = interEnd - 1; j >= interStart; j--) {
                        int interceptorIndex = iEdges[j];
                        var interceptorObject = (GraphInterceptor<Object>) oldValuesToRelease[interceptorIndex];
                        if (interceptorObject == null) {
                            interceptorObject = (GraphInterceptor<Object>) rootRawObjects[interceptorIndex];
                        }
                        if (interceptorObject != null) {
                            oldVal = interceptorObject.beforeRelease(oldVal);
                        }
                    }

                    if (oldVal instanceof Lifecycle lifecycle) {
                        lifecycle.release();
                    } else if (oldVal instanceof AutoCloseable closeable) {
                        closeable.close();
                    }
                } catch (Exception _) {
                }
            }
        }
        this.delegate = rootGraph;
    }

    public void rollback() {
        var length = tmpArray.length;
        for (var i = length - 1; i >= 0; i--) {
            var currentTmp = tmpArray[i];
            var original = rootRawObjects[i];
            if (currentTmp != original && currentTmp != null) {
                try {
                    if (currentTmp instanceof Lifecycle lifecycle) {
                        lifecycle.release();
                    } else if (currentTmp instanceof Closeable closeable) {
                        closeable.close();
                    }
                } catch (Exception _) {
                }
            }
        }
    }

    @Override
    public void refresh(Node<?> fromNode) {
        rootGraph.refresh(fromNode);
    }

    private static class TmpValueOf<T> implements ValueOf<T> {
        public volatile Graph tmpGraph;
        private final NodeImpl<? extends T> node;

        private TmpValueOf(NodeImpl<? extends T> node, Graph tmpGraph) {
            this.node = node;
            this.tmpGraph = tmpGraph;
        }

        @Override
        public T get() {
            return this.tmpGraph.get(this.node);
        }
    }
}
