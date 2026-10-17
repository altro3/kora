package io.koraframework.application.graph.internal.optimized;

import io.koraframework.application.graph.*;
import io.koraframework.application.graph.GraphCondition.*;
import io.koraframework.application.graph.internal.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public final class OptimizedGraphImpl implements InitializedGraph {

    private static final long SLOW_NODE_INIT_THRESHOLD_NANOS = Long.parseLong(System.getProperty("kora.graph.slowNodeInitThresholdMillis", "100")) * 1_000_000L;

    final Logger logger;
    private final ApplicationGraphDraw draw;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final AtomicReferenceArray<@Nullable Object> objects;
    private final BitSet refreshListenerIndices = new BitSet();
    private final AtomicReferenceArray<@Nullable ConditionResult> conditionResultsCache;

    public OptimizedGraphImpl(ApplicationGraphDraw draw) {
        this.draw = draw;
        this.logger = LoggerFactory.getLogger(draw.getRoot());
        this.objects = new AtomicReferenceArray<>(draw.size());
        this.conditionResultsCache = new AtomicReferenceArray<>(draw.size());
    }

    @Override
    public ApplicationGraphDraw draw() {
        return this.draw;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Node<? extends T> node) {
        var index = ((NodeImpl<?>) node).index;
        var val = this.objects.get(index);
        if (val == null) {
            throw new IllegalStateException("Graph node value was not initialized: " + node);
        }
        if (val instanceof ConditionFailedValue failedValue) {
            throw new IllegalStateException("Graph node value was not initialized because condition failed: " + failedValue.reason);
        }
        return (T) val;
    }

    @Override
    public GraphCondition condition(Node<? extends GraphCondition> node) {
        int index = ((NodeImpl<?>) node).index;
        var cached = this.conditionResultsCache.get(index);
        if (cached != null) {
            return () -> cached;
        }
        var computed = get(node).eval();
        if (this.conditionResultsCache.compareAndSet(index, null, computed)) {
            return () -> computed;
        }
        var finalCached = this.conditionResultsCache.get(index);
        var result = finalCached != null ? finalCached : computed;
        return () -> result;
    }

    @Override
    public <T> ValueOf<T> valueOf(Node<? extends T> node) {
        return () -> OptimizedGraphImpl.this.get(node);
    }

    @Override
    public <T> PromiseOf<T> promiseOf(Node<? extends T> node) {
        return () -> Optional.of(OptimizedGraphImpl.this.get(node));
    }

    @Override
    @SafeVarargs
    public final <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... nodes) {
        return () -> Optional.of(this.getOneOf(nodes));
    }

    @Override
    public void init() {
        this.mutationLock.lock();
        logger.debug("Dependency container initializing...");
        final long started = System.nanoTime();
        try {
            this.initializeSubgraph(0);
            if (logger.isDebugEnabled()) {
                logger.debug("Dependency container initialized in {}ms", tookForLogging(started));
            }
        } catch (Exception e) {
            logger.debug("Dependency container initialization failed", e);
            throw e;
        } finally {
            this.mutationLock.unlock();
        }
    }

    @Override
    public void refresh(Node<?> fromNodeRaw) {
        var fromNode = (NodeImpl<?>) fromNodeRaw;
        this.mutationLock.lock();
        var fromNodeObj = this.objects.get(fromNode.index);
        if (logger.isDebugEnabled()) {
            logger.debug("Dependency container refreshing from node {} of class {}...", fromNode.index, fromNodeObj != null ? fromNodeObj.getClass() : "null");
        }
        final long started = System.nanoTime();
        try {
            this.initializeSubgraph(fromNode.index);
            if (logger.isDebugEnabled()) {
                logger.debug("Dependency container refreshed in {}ms", tookForLogging(started));
            }
        } catch (Throwable e) {
            logger.debug("Dependency container refresh error", e);
            throw e;
        } finally {
            this.mutationLock.unlock();
        }
    }

    @Override
    public void release() {
        this.mutationLock.lock();
        logger.debug("Dependency container releasing...");
        final long started = System.nanoTime();
        try {
            this.releaseAllInternal();
            if (logger.isDebugEnabled()) {
                logger.debug("Dependency container released in {}ms", tookForLogging(started));
            }
        } catch (Exception e) {
            logger.debug("Dependency container releasing failed", e);
            throw e;
        } finally {
            this.mutationLock.unlock();
        }
    }

    private void releaseAllInternal() {
        var nodes = this.draw.getNodes();
        var size = nodes.size();
        Throwable error = null;

        for (int i = size - 1; i >= 0; i--) {
            var node = (NodeImpl<?>) nodes.get(i);
            try {
                this.releaseSingleNode(node);
            } catch (Throwable t) {
                if (error == null) {
                    error = t;
                } else {
                    error.addSuppressed(t);
                }
            }
        }

        if (error != null) {
            if (error instanceof RuntimeException re) throw re;
            if (error instanceof Error err) throw err;
            throw new RuntimeException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void releaseSingleNode(NodeImpl<T> node) throws Exception {
        var object = (T) this.objects.get(node.index);
        if (object == null) return;

        var interceptors = node.interceptors;
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            var interceptorNode = (NodeImpl<? extends GraphInterceptor<T>>) interceptors.get(i);
            var interceptor = (GraphInterceptor<T>) objects.get(interceptorNode.index);
            if (interceptor != null) {
                object = interceptor.beforeRelease(object);
            }
        }

        if (object instanceof Lifecycle lifecycle) {
            lifecycle.release();
        } else if (object instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private void fireRefreshListeners() {
        for (int i = refreshListenerIndices.nextSetBit(0); i >= 0; i = refreshListenerIndices.nextSetBit(i + 1)) {
            var object = objects.get(i);
            if (object instanceof RefreshListener listener) {
                try {
                    listener.graphRefreshed();
                } catch (Throwable t) {
                    logger.warn("Error calling refresh listener for node at index {}", i, t);
                }
            }
        }
    }

    private static long tookForLogging(long started) {
        long tookNanos = System.nanoTime() - started;
        return tookNanos / 1_000_000;
    }

    private void initializeSubgraph(int startFrom) {
        logger.trace("Materializing graph objects {}", startFrom);

        var nodes = draw.getNodes();
        int size = nodes.size();

        var dependentNodes = new BitSet(size);
        if (startFrom == 0) {
            dependentNodes.set(0, size);
        } else {
            dependentNodes.set(startFrom);
            for (int i = startFrom; i < size; i++) {
                var node = (NodeImpl<?>) nodes.get(i);
                boolean dependsOnChanged = false;

                var createDeps = node.createDependencies;
                for (int j = 0; j < createDeps.size(); j++) {
                    if (dependentNodes.get(((NodeImpl<?>) createDeps.get(j)).index)) {
                        dependsOnChanged = true;
                        break;
                    }
                }
                if (!dependsOnChanged) {
                    var refreshDeps = node.refreshDependencies;
                    for (int j = 0; j < refreshDeps.size(); j++) {
                        if (dependentNodes.get(((NodeImpl<?>) refreshDeps.get(j)).index)) {
                            dependsOnChanged = true;
                            break;
                        }
                    }
                }
                if (!dependsOnChanged) {
                    var interceptorDeps = node.interceptors;
                    for (int j = 0; j < interceptorDeps.size(); j++) {
                        if (dependentNodes.get(((NodeImpl<?>) interceptorDeps.get(j)).index)) {
                            dependsOnChanged = true;
                            break;
                        }
                    }
                }

                if (dependsOnChanged) {
                    dependentNodes.set(i);
                }
            }
        }

        var isDebug = logger.isDebugEnabled();
        var isTrace = logger.isTraceEnabled();

        var tmpGraph = new OptimizedTmpGraph(this);
        final var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();

        int[] temporaryDepCounts = new int[size];
        int[] downstreamCounts = new int[size];
        int totalDependentTasks = 0;

        for (int i = 0; i < size; i++) {
            if (i < startFrom || !dependentNodes.get(i)) {
                continue;
            }

            totalDependentTasks++;
            var node = (NodeImpl<?>) nodes.get(i);
            int depCount = 0;

            var createDeps = node.createDependencies;
            for (int j = 0; j < createDeps.size(); j++) {
                int depIdx = ((NodeImpl<?>) createDeps.get(j)).index;
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) {
                    downstreamCounts[depIdx]++;
                    depCount++;
                }
            }
            var refreshDeps = node.refreshDependencies;
            for (int j = 0; j < refreshDeps.size(); j++) {
                int depIdx = ((NodeImpl<?>) refreshDeps.get(j)).index;
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) {
                    downstreamCounts[depIdx]++;
                    depCount++;
                }
            }
            var interceptorDeps = node.interceptors;
            for (int j = 0; j < interceptorDeps.size(); j++) {
                int depIdx = ((NodeImpl<?>) interceptorDeps.get(j)).index;
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) {
                    downstreamCounts[depIdx]++;
                    depCount++;
                }
            }
            temporaryDepCounts[i] = depCount;
        }

        if (totalDependentTasks == 0) {
            tmpGraph.commit(startFrom);
            fireRefreshListeners();
            return;
        }

        int[][] downstream = new int[size][];
        for (int i = 0; i < size; i++) {
            if (downstreamCounts[i] > 0) {
                downstream[i] = new int[downstreamCounts[i]];
            }
        }

        int[] downstreamOffsets = new int[size];
        for (int i = startFrom; i < size; i++) {
            if (!dependentNodes.get(i)) continue;
            var node = (NodeImpl<?>) nodes.get(i);

            var createDeps = node.createDependencies;
            for (int j = 0; j < createDeps.size(); j++) {
                int depIdx = ((NodeImpl<?>) createDeps.get(j)).index;
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) {
                    downstream[depIdx][downstreamOffsets[depIdx]++] = i;
                }
            }
            var refreshDeps = node.refreshDependencies;
            for (int j = 0; j < refreshDeps.size(); j++) {
                int depIdx = ((NodeImpl<?>) refreshDeps.get(j)).index;
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) {
                    downstream[depIdx][downstreamOffsets[depIdx]++] = i;
                }
            }
            var interceptorDeps = node.interceptors;
            for (int j = 0; j < interceptorDeps.size(); j++) {
                int depIdx = ((NodeImpl<?>) interceptorDeps.get(j)).index;
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) {
                    downstream[depIdx][downstreamOffsets[depIdx]++] = i;
                }
            }
        }

        var remainingDepsArray = new java.util.concurrent.atomic.AtomicIntegerArray(size);
        for (int i = 0; i < size; i++) {
            remainingDepsArray.set(i, temporaryDepCounts[i]);
        }

        final var latch = new CountDownLatch(totalDependentTasks);
        final Runnable[] tasks = new Runnable[size];

        try (var virtualThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {

            for (int i = startFrom; i < size; i++) {
                if (dependentNodes.get(i)) {
                    final int targetIndex = i;
                    tasks[i] = () -> {
                        if (!errors.isEmpty()) {
                            latch.countDown();
                            return;
                        }

                        var currentNode = (NodeImpl<?>) nodes.get(targetIndex);
                        long start = isDebug ? System.nanoTime() : 0L;
                        try {
                            tmpGraph.createNode(currentNode, startFrom, isTrace);
                            if (isDebug) {
                                long tookNanos = System.nanoTime() - start;
                                if (tookNanos > SLOW_NODE_INIT_THRESHOLD_NANOS) {
                                    logger.debug("Initialized node {} at index {} in {}ms", currentNode.type(), currentNode.index, tookNanos / 1_000_000);
                                }
                            }
                            latch.countDown();

                            var nextNodes = downstream[targetIndex];
                            if (nextNodes != null) {
                                for (int k = 0; k < nextNodes.length; k++) {
                                    int nextIdx = nextNodes[k];
                                    if (remainingDepsArray.decrementAndGet(nextIdx) == 0) {
                                        virtualThreadExecutor.execute(tasks[nextIdx]);
                                    }
                                }
                            }
                        } catch (Throwable throwable) {
                            errors.add(throwable);
                            latch.countDown();
                            cascadeCancel(targetIndex, downstream, latch);
                        }
                    };
                }
            }

            for (int i = startFrom; i < size; i++) {
                if (dependentNodes.get(i) && temporaryDepCounts[i] == 0) {
                    virtualThreadExecutor.execute(tasks[i]);
                }
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(e);
            }
        }

        if (!errors.isEmpty()) {
            tmpGraph.rollback();
            var rootCause = errors.get(0);
            if (rootCause instanceof CompletionException ce) {
                rootCause = ce.getCause();
            }

            switch (rootCause) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                case null -> throw new IllegalStateException("Kora internal error: graph initialization failed with null error");
                default -> throw new IllegalStateException("Application graph failed to initialize; see cause for failed component", rootCause);
            }
        }

        tmpGraph.commit(startFrom);
        fireRefreshListeners();
    }

    private void cascadeCancel(int index, int[][] downstream, CountDownLatch latch) {
        int[] nextNodes = downstream[index];
        if (nextNodes != null) {
            for (int k = 0; k < nextNodes.length; k++) {
                int nextIdx = nextNodes[k];
                latch.countDown();
                cascadeCancel(nextIdx, downstream, latch);
            }
        }
    }

    AtomicReferenceArray<@Nullable Object> getRawObjects() {
        return objects;
    }

    BitSet getRefreshListenerIndices() {
        return refreshListenerIndices;
    }

    AtomicReferenceArray<@Nullable ConditionResult> getConditionResultsCache() {
        return conditionResultsCache;
    }

//    private static class DependencyInitializationFailedException extends RuntimeException {
//        @Override
//        public Throwable fillInStackTrace() {
//            return this;
//        }
//    }

    static final class ConditionFailedValue {
        final String reason;

        ConditionFailedValue(ConditionResult.Failed failed) {
            this.reason = failed.reason();
        }
    }
}
