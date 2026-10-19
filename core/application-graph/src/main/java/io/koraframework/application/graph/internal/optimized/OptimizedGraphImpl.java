package io.koraframework.application.graph.internal.optimized;

import io.koraframework.application.graph.*;
import io.koraframework.application.graph.internal.*;
import org.jspecify.annotations.*;
import org.slf4j.*;

import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static io.koraframework.application.graph.GraphCondition.*;

public final class OptimizedGraphImpl implements InitializedGraph {

    private static final long SLOW_NODE_INIT_THRESHOLD_NANOS = Long.parseLong(System.getProperty("kora.graph.slowNodeInitThresholdMillis", "100")) * 1_000_000L;

    private static final VarHandle REMAINING_DEPS_VH = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle OBJECTS_VH = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle GLOBAL_REMAINING_VH;
    private static final VarHandle ERROR_CHANNEL_VH;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            GLOBAL_REMAINING_VH = l.findVarHandle(OptimizedGraphImpl.class, "globalRemainingTasks", int.class);
            ERROR_CHANNEL_VH = l.findVarHandle(OptimizedGraphImpl.class, "errorChannel", Throwable.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final Logger logger;
    private final ApplicationGraphDraw draw;
    private final ReentrantLock mutationLock = new ReentrantLock();
    Object[] graphObjects;
    private final BitSet refreshListenerIndices = new BitSet();
    private final AtomicReferenceArray<@Nullable ConditionResult> conditionResultsCache;

    private volatile int globalRemainingTasks;
    private volatile Throwable errorChannel;
    private volatile Thread mainThread;

    public OptimizedGraphImpl(ApplicationGraphDraw draw) {
        this.draw = draw;
        this.logger = LoggerFactory.getLogger(draw.getRoot());
        this.graphObjects = new Object[draw.size()];
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
        var val = OBJECTS_VH.getVolatile(graphObjects, index);
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
        var fromNodeObj = OBJECTS_VH.getVolatile(graphObjects, fromNode.index);
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

    private void initializeSubgraph(int startFrom) {
        logger.trace("Materializing graph objects {}", startFrom);

        int size = draw.size();
        var dependentNodes = new BitSet(size);

        if (startFrom == 0) {
            dependentNodes.set(0, size);
        } else {
            dependentNodes.set(startFrom);
            int[] cEdges = draw.getCreateEdges();
            int[] cOffsets = draw.getCreateOffsets();
            int[] rEdges = draw.getRefreshEdges();
            int[] rOffsets = draw.getRefreshOffsets();
            int[] iEdges = draw.getInterceptorEdges();
            int[] iOffsets = draw.getInterceptorOffsets();

            for (int i = startFrom; i < size; i++) {
                boolean dependsOnChanged = false;

                int start = cOffsets[i], end = cOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    if (dependentNodes.get(cEdges[j])) { dependsOnChanged = true; break; }
                }
                if (!dependsOnChanged) {
                    start = rOffsets[i]; end = rOffsets[i + 1];
                    for (int j = start; j < end; j++) {
                        if (dependentNodes.get(rEdges[j])) { dependsOnChanged = true; break; }
                    }
                }
                if (!dependsOnChanged) {
                    start = iOffsets[i]; end = iOffsets[i + 1];
                    for (int j = start; j < end; j++) {
                        if (dependentNodes.get(iEdges[j])) { dependsOnChanged = true; break; }
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

        int[] temporaryDepCounts = new int[size];
        int totalDependentTasks = 0;

        int[] cEdges = draw.getCreateEdges();
        int[] cOffsets = draw.getCreateOffsets();
        int[] rEdges = draw.getRefreshEdges();
        int[] rOffsets = draw.getRefreshOffsets();
        int[] iEdges = draw.getInterceptorEdges();
        int[] iOffsets = draw.getInterceptorOffsets();

        for (int i = 0; i < size; i++) {
            if (i < startFrom || !dependentNodes.get(i)) {
                continue;
            }
            totalDependentTasks++;
            int depCount = 0;

            int start = cOffsets[i], end = cOffsets[i + 1];
            for (int j = start; j < end; j++) {
                int depIdx = cEdges[j];
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) depCount++;
            }
            start = rOffsets[i]; end = rOffsets[i + 1];
            for (int j = start; j < end; j++) {
                int depIdx = rEdges[j];
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) depCount++;
            }
            start = iOffsets[i]; end = iOffsets[i + 1];
            for (int j = start; j < end; j++) {
                int depIdx = iEdges[j];
                if (depIdx >= startFrom && dependentNodes.get(depIdx)) depCount++;
            }
            temporaryDepCounts[i] = depCount;
        }

        if (totalDependentTasks == 0) {
            tmpGraph.commit(startFrom);
            fireRefreshListeners();
            return;
        }

        int[] downstreamHead = new int[size + 1];
        int[] downstreamEdges = draw.getDownstreamEdges();
        int[] downstreamOffsets = draw.getDownstreamOffsets();

        if (downstreamEdges == null || downstreamEdges.length == 0) {
            int[] currentOffsets = new int[size];
            for (int i = startFrom; i < size; i++) {
                if (!dependentNodes.get(i)) continue;

                int start = cOffsets[i], end = cOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    int depIdx = cEdges[j];
                    if (depIdx >= startFrom && dependentNodes.get(depIdx)) currentOffsets[depIdx]++;
                }
                start = rOffsets[i]; end = rOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    int depIdx = rEdges[j];
                    if (depIdx >= startFrom && dependentNodes.get(depIdx)) currentOffsets[depIdx]++;
                }
                start = iOffsets[i]; end = iOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    int depIdx = iEdges[j];
                    if (depIdx >= startFrom && dependentNodes.get(depIdx)) currentOffsets[depIdx]++;
                }
            }

            int currentSum = 0;
            for (int i = 0; i < size; i++) {
                downstreamHead[i] = currentSum;
                currentSum += currentOffsets[i];
            }
            downstreamHead[size] = currentSum;
            System.arraycopy(downstreamHead, 0, currentOffsets, 0, size);

            downstreamEdges = new int[cEdges.length + rEdges.length + iEdges.length];
            for (int i = startFrom; i < size; i++) {
                if (!dependentNodes.get(i)) continue;

                int start = cOffsets[i], end = cOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    int depIdx = cEdges[j];
                    if (depIdx >= startFrom && dependentNodes.get(depIdx)) downstreamEdges[currentOffsets[depIdx]++] = i;
                }
                start = rOffsets[i]; end = rOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    int depIdx = rEdges[j];
                    if (depIdx >= startFrom && dependentNodes.get(depIdx)) downstreamEdges[currentOffsets[depIdx]++] = i;
                }
                start = iOffsets[i]; end = iOffsets[i + 1];
                for (int j = start; j < end; j++) {
                    int depIdx = iEdges[j];
                    if (depIdx >= startFrom && dependentNodes.get(depIdx)) downstreamEdges[currentOffsets[depIdx]++] = i;
                }
            }
        } else {
            downstreamHead = downstreamOffsets;
        }

        this.globalRemainingTasks = totalDependentTasks;
        this.errorChannel = null;
        this.mainThread = Thread.currentThread();

        int[] finalDownstreamHead = downstreamHead;
        int[] finalDownstreamEdges = downstreamEdges;

        for (int i = startFrom; i < size; i++) {
            if (dependentNodes.get(i) && temporaryDepCounts[i] == 0) {
                final int rootIdx = i;
                Thread.ofVirtual().start(() -> executeNodeTask(rootIdx, startFrom, isDebug, isTrace, tmpGraph, temporaryDepCounts, finalDownstreamHead, finalDownstreamEdges));
            }
        }

        while (globalRemainingTasks > 0 && errorChannel == null) {
            Thread.onSpinWait();
            if (globalRemainingTasks > 0 && errorChannel == null) {
                LockSupport.park();
            }
        }

        Throwable error = this.errorChannel;
        if (error != null) {
            tmpGraph.rollback();
            if (error instanceof CompletionException ce) {
                error = ce.getCause();
            }
            switch (error) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                case null -> throw new IllegalStateException("Kora internal error: graph initialization failed with null error");
                default -> throw new IllegalStateException("Application graph failed to initialize; see cause for failed component", error);
            }
        }

        tmpGraph.commit(startFrom);
        fireRefreshListeners();
    }

    @SuppressWarnings("unchecked")
    private void executeNodeTask(int targetIndex, int startFrom, boolean isDebug, boolean isTrace, OptimizedTmpGraph tmpGraph, int[] remainingDepsArray, int[] downstreamHead, int[] downstreamEdges) {
        if (errorChannel != null) {
            return;
        }

        var currentNode = (NodeImpl<?>) draw.getNodeByIndex(targetIndex);
        long start = isDebug ? System.nanoTime() : 0L;
        try {
            var condition = draw.getNodeCondition(targetIndex);
            if (condition != null) {
                var condResult = condition.apply(this);
                conditionResultsCache.set(targetIndex, condResult);
                if (condResult instanceof ConditionResult.Failed) {
                    OBJECTS_VH.setRelease(graphObjects, targetIndex, new ConditionFailedValue("Condition failed"));
                    int globalLeft = (int) GLOBAL_REMAINING_VH.getAndAdd(this, -1) - 1;
                    if (globalLeft == 0) LockSupport.unpark(mainThread);
                    return;
                }
            }

            tmpGraph.createNode(currentNode, startFrom, isTrace);

            Object instance = currentNode.factory.get(this);

            int[] interOffsets = draw.getInterceptorOffsets();
            int[] interEdges = draw.getInterceptorEdges();
            int startInt = interOffsets[targetIndex];
            int endInt = interOffsets[targetIndex + 1];
            for (int i = startInt; i < endInt; i++) {
                int interceptorIndex = interEdges[i];
                var interceptor = (GraphInterceptor<Object>) OBJECTS_VH.getVolatile(graphObjects, interceptorIndex);
                if (interceptor != null) {
                    instance = interceptor.afterInit(instance);
                }
            }

            if (instance instanceof Lifecycle lifecycle) {
                lifecycle.init();
            }

            OBJECTS_VH.setRelease(graphObjects, targetIndex, instance);

            if (isDebug) {
                long tookNanos = System.nanoTime() - start;
                if (tookNanos > SLOW_NODE_INIT_THRESHOLD_NANOS) {
                    logger.debug("Initialized node {} at index {} in {}ms", currentNode.type(), currentNode.index, tookNanos / 1_000_000);
                }
            }

            int globalLeft = (int) GLOBAL_REMAINING_VH.getAndAdd(this, -1) - 1;
            if (globalLeft == 0) {
                LockSupport.unpark(mainThread);
                return;
            }

            int dsStart = downstreamHead[targetIndex];
            int dsEnd = downstreamHead[targetIndex + 1];
            for (int k = dsStart; k < dsEnd; k++) {
                int nextIdx = downstreamEdges[k];
                int depsLeft = (int) REMAINING_DEPS_VH.getAndAdd(remainingDepsArray, nextIdx, -1) - 1;
                if (depsLeft == 0) {
                    Thread.ofVirtual().start(() -> executeNodeTask(nextIdx, startFrom, isDebug, isTrace, tmpGraph, remainingDepsArray, downstreamHead, downstreamEdges));
                }
            }
        } catch (Throwable throwable) {
            if (ERROR_CHANNEL_VH.compareAndSet(this, null, throwable)) {
                LockSupport.unpark(mainThread);
            }
            cascadeCancel(targetIndex, downstreamHead, downstreamEdges);
        }
    }

    private void cascadeCancel(int index, int[] downstreamHead, int[] downstreamEdges) {
        int globalLeft = (int) GLOBAL_REMAINING_VH.getAndAdd(this, -1) - 1;
        if (globalLeft == 0) {
            LockSupport.unpark(mainThread);
        }
        int dsStart = downstreamHead[index];
        int dsEnd = downstreamHead[index + 1];
        for (int k = dsStart; k < dsEnd; k++) {
            cascadeCancel(downstreamEdges[k], downstreamHead, downstreamEdges);
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

    @SuppressWarnings("unchecked")
    private void releaseAllInternal() {
        int size = draw.size();
        Throwable error = null;

        for (int i = size - 1; i >= 0; i--) {
            try {
                var object = OBJECTS_VH.getVolatile(graphObjects, i);
                if (object == null || object instanceof ConditionFailedValue) continue;

                int[] interOffsets = draw.getInterceptorOffsets();
                int[] interEdges = draw.getInterceptorEdges();
                int start = interOffsets[i];
                int end = interOffsets[i + 1];

                for (int j = end - 1; j >= start; j--) {
                    int interceptorIndex = interEdges[j];
                    var interceptor = (GraphInterceptor<Object>) OBJECTS_VH.getVolatile(graphObjects, interceptorIndex);
                    if (interceptor != null) {
                        object = interceptor.beforeRelease(object);
                    }
                }

                if (object instanceof Lifecycle lifecycle) {
                    lifecycle.release();
                } else if (object instanceof AutoCloseable closeable) {
                    closeable.close();
                }
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

    private void fireRefreshListeners() {
        for (int i = refreshListenerIndices.nextSetBit(0); i >= 0; i = refreshListenerIndices.nextSetBit(i + 1)) {
            var object = OBJECTS_VH.getVolatile(graphObjects, i);
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

    AtomicReferenceArray<@Nullable Object> getRawObjects() {
        return new AtomicReferenceArray<>(graphObjects);
    }

    BitSet getRefreshListenerIndices() {
        return refreshListenerIndices;
    }

    AtomicReferenceArray<@Nullable ConditionResult> getConditionResultsCache() {
        return conditionResultsCache;
    }

    static final class ConditionFailedValue {
        final String reason;

        ConditionFailedValue(String reason) {
            this.reason = reason;
        }
    }
}
