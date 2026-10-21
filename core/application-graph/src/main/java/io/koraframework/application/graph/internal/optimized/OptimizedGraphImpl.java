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
import static java.lang.IO.println;

public final class OptimizedGraphImpl implements InitializedGraph {

    private static final long SLOW_NODE_INIT_THRESHOLD_NANOS = Long.parseLong(System.getProperty("kora.graph.slowNodeInitThresholdMillis", "100")) * 1_000_000L;
    private static final VarHandle OBJECTS_VH = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle CONDITION_VH = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle GLOBAL_REMAINING_VH;
    private static final VarHandle ERROR_CHANNEL_VH;

    static {
        try {
            var l = MethodHandles.lookup();
            GLOBAL_REMAINING_VH = l.findVarHandle(OptimizedGraphImpl.class, "globalRemainingTasks", int.class);
            ERROR_CHANNEL_VH = l.findVarHandle(OptimizedGraphImpl.class, "errorChannel", Throwable.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final Logger logger;
    private final ApplicationGraphDraw draw;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final AtomicIntegerArray nodeCreationStates; // 0 - не создано, 1 - создается, 2 - готово
    Object[] graphObjects;
    private final BitSet refreshListenerIndices = new BitSet();
    private final int[] conditionResultsCache;

    private volatile int globalRemainingTasks;
    private volatile @Nullable Throwable errorChannel;
    private volatile @Nullable Thread mainThread;

    public OptimizedGraphImpl(ApplicationGraphDraw draw) {
        this.draw = draw;
        this.logger = LoggerFactory.getLogger(draw.getRoot());
        this.graphObjects = new Object[draw.size()];
        this.conditionResultsCache = new int[draw.size()];
        this.nodeCreationStates = new AtomicIntegerArray(draw.size());
    }

    @Override
    public ApplicationGraphDraw draw() {
        return draw;
    }

    public AtomicIntegerArray getNodeCreationStates() {
        return nodeCreationStates;
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
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getNullable(Node<? extends T> node) {
        var index = ((NodeImpl<?>) node).index;
        var val = OBJECTS_VH.getVolatile(graphObjects, index);
        if (val == null || val instanceof ConditionFailedValue) {
            return null;
        }
        return (T) val;
    }

    @Override
    public GraphCondition condition(Node<? extends GraphCondition> node) {
        var index = ((NodeImpl<?>) node).index;
        var cached = (int) CONDITION_VH.getVolatile(conditionResultsCache, index);
        if (cached != 0) {
            return () -> cached == 1 ? new ConditionResult.Matched("Cached matched") : new ConditionResult.Failed("Cached failed");
        }
        var computed = get(node).eval();
        var targetVal = computed instanceof ConditionResult.Matched ? 1 : 2;
        if (CONDITION_VH.compareAndSet(conditionResultsCache, index, 0, targetVal)) {
            return () -> computed;
        }
        var finalCached = (int) CONDITION_VH.getVolatile(conditionResultsCache, index);
        return () -> finalCached == 1 ? new ConditionResult.Matched("Cached matched") : new ConditionResult.Failed("Cached failed");
    }

    @Override
    public <T> ValueOf<T> valueOf(Node<? extends T> node) {
        return () -> get(node);
    }

    @Override
    public <T> PromiseOf<T> promiseOf(Node<? extends T> node) {
        return () -> Optional.of(get(node));
    }

    @Override
    @SafeVarargs
    public final <N, V> PromiseOf<V> getOnePromiseOf(NodeWithMapper<N, V>... nodes) {
        return () -> Optional.of(getOneOf(nodes));
    }

    @Override
    public void init() {
        mutationLock.lock();
        logger.debug("Dependency container initializing...");
        var started = System.nanoTime();
        try {
            initializeSubgraph((short) 0, true);
            if (logger.isDebugEnabled()) {
                logger.debug("Dependency container initialized in {}ms", tookForLogging(started));
            }
        } catch (Exception e) {
            logger.debug("Dependency container initialization failed", e);
            throw e;
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public void refresh(Node<?> fromNodeRaw) {
        var fromNode = (NodeImpl<?>) fromNodeRaw;
        mutationLock.lock();
        var fromNodeObj = OBJECTS_VH.getVolatile(graphObjects, fromNode.index);
        if (logger.isDebugEnabled()) {
            logger.debug("Dependency container refreshing from node {} of class {}...", fromNode.index, fromNodeObj != null ? fromNodeObj.getClass() : "null");
        }
        var started = System.nanoTime();
        try {
            initializeSubgraph(fromNode.index, false);
            if (logger.isDebugEnabled()) {
                logger.debug("Dependency container refreshed in {}ms", tookForLogging(started));
            }
        } catch (Throwable e) {
            logger.debug("Dependency container refresh error", e);
            throw e;
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public void release() {
        mutationLock.lock();
        logger.debug("Dependency container releasing...");
        var started = System.nanoTime();
        try {
            releaseAllInternal();
            if (logger.isDebugEnabled()) {
                logger.debug("Dependency container released in {}ms", tookForLogging(started));
            }
        } catch (Exception e) {
            logger.debug("Dependency container releasing failed", e);
            throw e;
        } finally {
            mutationLock.unlock();
        }
    }

    private static long tookForLogging(long started) {
        var tookNanos = System.nanoTime() - started;
        return tookNanos / 1_000_000;
    }

    BitSet getRefreshListenerIndices() {
        return refreshListenerIndices;
    }

    int[] getConditionResultsCache() {
        return conditionResultsCache;
    }

    private void fireRefreshListeners() {
        for (var i = refreshListenerIndices.nextSetBit(0); i >= 0; i = refreshListenerIndices.nextSetBit(i + 1)) {
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

    private void initializeSubgraph(short startFrom, boolean isFullInit) {
        logger.trace("Materializing graph objects {}", startFrom);

        var size = draw.size();
        var dependentNodes = new BitSet(size);

        if (isFullInit) {
            dependentNodes.set(0, size);
        } else {
            dependentNodes.set(startFrom);
            var rEdges = draw.getRefreshEdges();
            var rOffsets = draw.getRefreshOffsets();
            var iEdges = draw.getInterceptorEdges();
            var iOffsets = draw.getInterceptorOffsets();

            for (var i = startFrom; i < size; i++) {
                var dependsOnChanged = false;
                var start = rOffsets[i];
                var end = rOffsets[i + 1];
                for (var j = start; j < end; j++) {
                    if (dependentNodes.get(rEdges[j])) {
                        dependsOnChanged = true;
                        break;
                    }
                }
                if (!dependsOnChanged) {
                    start = iOffsets[i];
                    end = iOffsets[i + 1];
                    for (var j = start; j < end; j++) {
                        if (dependentNodes.get(iEdges[j])) {
                            dependsOnChanged = true;
                            break;
                        }
                    }
                }

                if (dependsOnChanged) {
                    dependentNodes.set(i);
                }
            }

            for (int i = size - 1; i >= startFrom; i--) {
                if (dependentNodes.get(i)) {
                    var oldInstance = OBJECTS_VH.getVolatile(graphObjects, i);
                    if (oldInstance != null && !(oldInstance instanceof ConditionFailedValue)) {
                        try {
                            var interOffsets = draw.getInterceptorOffsets();
                            var interEdges = draw.getInterceptorEdges();
                            var startInt = interOffsets[i];
                            var endInt = interOffsets[i + 1];
                            for (var j = (short) (endInt - 1); j >= startInt; j--) {
                                var interceptorIndex = interEdges[j];
                                var interceptor = (GraphInterceptor<Object>) OBJECTS_VH.getVolatile(graphObjects, interceptorIndex);
                                if (interceptor != null) {
                                    oldInstance = interceptor.beforeRelease(oldInstance);
                                }
                            }

                            if (oldInstance instanceof Lifecycle lifecycleOld) {
                                lifecycleOld.release();
                            } else if (oldInstance instanceof AutoCloseable closeableOld) {
                                closeableOld.close();
                            }
                        } catch (Throwable t) {
                            logger.warn("Error releasing old instance during refresh at index {}", i, t);
                        }
                    }
                }
            }
        }

        var isDebug = logger.isDebugEnabled();
        var isTrace = logger.isTraceEnabled();
        var tmpGraph = new OptimizedTmpGraph(this);

        var tasks = new NodeTask[size];
        var totalDependentTasks = 0;

        var cEdges = draw.getCreateEdges();
        var cOffsets = draw.getCreateOffsets();
        var rEdges = draw.getRefreshEdges();
        var rOffsets = draw.getRefreshOffsets();
        var iEdges = draw.getInterceptorEdges();
        var iOffsets = draw.getInterceptorOffsets();

        short localIterationId = 0;
        var localVisitedCache = new short[size];

        for (short i = 0; i < size; i++) {
            if (i < startFrom || !dependentNodes.get(i)) {
                continue;
            }
            totalDependentTasks++;
            var depCount = 0;
            var iterId = ++localIterationId;

            var start = cOffsets[i];
            var end = cOffsets[i + 1];
            for (var j = start; j < end; j++) {
                var depIdx = cEdges[j];
                if (depIdx >= startFrom && dependentNodes.get(depIdx) && localVisitedCache[depIdx] != iterId) {
                    localVisitedCache[depIdx] = iterId;
                    depCount++;
                }
            }
            start = rOffsets[i];
            end = rOffsets[i + 1];
            for (var j = start; j < end; j++) {
                var depIdx = rEdges[j];
                if (depIdx >= startFrom && dependentNodes.get(depIdx) && localVisitedCache[depIdx] != iterId) {
                    localVisitedCache[depIdx] = iterId;
                    depCount++;
                }
            }
            start = iOffsets[i];
            end = iOffsets[i + 1];
            for (var j = start; j < end; j++) {
                var depIdx = iEdges[j];
                if (depIdx >= startFrom && dependentNodes.get(depIdx) && localVisitedCache[depIdx] != iterId) {
                    localVisitedCache[depIdx] = iterId;
                    depCount++;
                }
            }
            tasks[i] = new NodeTask(i, depCount);
            // println("Node " + i + " total calculated remainingDeps: " + depCount);
        }

        if (totalDependentTasks == 0) {
            // println("totalDependentTasks == 0. Fast-committing.");
            tmpGraph.commit(startFrom);
            fireRefreshListeners();
            return;
        }

        globalRemainingTasks = totalDependentTasks;
        errorChannel = null;
        mainThread = Thread.currentThread();

        for (short i = startFrom; i < size; i++) {
            if (dependentNodes.get(i)) {
                nodeCreationStates.set(i, 0);
            }
        }

        for (var i = startFrom; i < size; i++) {
            if (dependentNodes.get(i) && tasks[i] != null && tasks[i].remainingDeps.get() == 0) {
                final var task = tasks[i];
                // println(" Spawning starter Thread for Node " + task.index);
                Thread.ofVirtual().start(() -> executeNodeTask(task, startFrom, isFullInit, isDebug, isTrace, tmpGraph, tasks));
            }
        }

        // println(" Main thread entering wait loop. globalRemainingTasks=" + globalRemainingTasks);
        while (globalRemainingTasks > 0 && errorChannel == null) {
            LockSupport.park();
        }
        // println(" Main thread broke wait loop. globalRemainingTasks=" + globalRemainingTasks + ", error=" + errorChannel);

        var error = errorChannel;
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
    private void executeNodeTask(
        NodeTask task,
        short startFrom,
        boolean isFullInit,
        boolean isDebug,
        boolean isTrace,
        OptimizedTmpGraph tmpGraph,
        NodeTask[] tasks
    ) {
        var targetIndex = task.index;
        var threadName = Thread.currentThread().toString();

        // println("[" + threadName + "] executeNodeTask enter for Node " + targetIndex);

        if (!task.started.compareAndSet(0, 1)) {
            // println("[" + threadName + "] Node " + targetIndex + " failed started CAS, exiting");
            return;
        }
        // println("[" + threadName + "] Node " + targetIndex + " won started CAS (0 -> 1)");

        if (errorChannel != null || task.isCancelled) {
            // println("[" + threadName + "] Node " + targetIndex + " aborted before execution, errorChannel=" + errorChannel + ", cancelled=" + task.isCancelled);
            decrementGlobalTasks(1, threadName, targetIndex, " (aborted)");
            return;
        }

        if (!nodeCreationStates.compareAndSet(targetIndex, 0, 1)) {
            var currentState = nodeCreationStates.get(targetIndex);
            // println("[" + threadName + "] Node " + targetIndex + " failed nodeCreationStates CAS (0 -> 1), current state=" + currentState);
            decrementGlobalTasks(1, threadName, targetIndex, " (skipped duplicate state)");
            notifyDownstreamConsumers(targetIndex, startFrom, isFullInit, isDebug, isTrace, tmpGraph, tasks, threadName, " (skipped duplicate state)");
            return;
        }
        // println("[" + threadName + "] Node " + targetIndex + " won nodeCreationStates CAS (0 -> 1)");

        var currentNode = (NodeImpl<?>) draw.getNodeByIndex(targetIndex);
        var start = isDebug ? System.nanoTime() : 0L;
        try {
            // println("[" + threadName + "] Node " + targetIndex + " calling tmpGraph.createNode");
            tmpGraph.createNode(currentNode, startFrom, isFullInit, isTrace);
            // println("[" + threadName + "] Node " + targetIndex + " finished tmpGraph.createNode");

            var currentVal = tmpGraph.getRawTmpArray()[targetIndex];

            if (currentVal instanceof ConditionFailedValue) {
                // println("[" + threadName + "] Node " + targetIndex + " execution stopped: ConditionFailedValue");
                OBJECTS_VH.setRelease(graphObjects, targetIndex, currentVal);
                nodeCreationStates.set(targetIndex, 2);
                decrementGlobalTasks(1, threadName, targetIndex, " (failed condition)");
                notifyDownstreamConsumers(targetIndex, startFrom, isFullInit, isDebug, isTrace, tmpGraph, tasks, threadName, " (failed condition)");
                return;
            }

            if (currentVal == null) {
                currentVal = graphObjects[targetIndex];
                // println("[" + threadName + "] Node " + targetIndex + " tmpArray value is null, rolled back to graphObjects value: " + currentVal);
            }

            if (currentVal != null) {
                OBJECTS_VH.setRelease(graphObjects, targetIndex, currentVal);
            }

            nodeCreationStates.set(targetIndex, 2);
            // println("[" + threadName + "] Node " + targetIndex + " nodeCreationStates set to 2 (READY)");

            if (isDebug) {
                var tookNanos = System.nanoTime() - start;
                if (tookNanos > SLOW_NODE_INIT_THRESHOLD_NANOS) {
                    logger.debug("Initialized node {} at index {} in {}ms", currentNode.type(), currentNode.index, tookNanos / 1_000_000);
                }
            }

            decrementGlobalTasks(1, threadName, targetIndex, " successfully finished");
            notifyDownstreamConsumers(targetIndex, startFrom, isFullInit, isDebug, isTrace, tmpGraph, tasks, threadName, "");
        } catch (Throwable t) {
            // println("[" + threadName + "] CRITICAL error at Node " + targetIndex + ": " + t.getClass().getName() + " - " + t.getMessage());
            t.printStackTrace();

            nodeCreationStates.set(targetIndex, 0);
            // println("[" + threadName + "] Node " + targetIndex + " nodeCreationStates reset to 0 due to error");

            if (ERROR_CHANNEL_VH.compareAndSet(this, null, t)) {
                // println("[" + threadName + "] Node " + targetIndex + " successfully set errorChannel, unparking main thread");
                LockSupport.unpark(mainThread);
            }
            decrementGlobalTasks(1, threadName, targetIndex, " failed with exception");
        }
    }

    private void notifyDownstreamConsumers(
        short targetIndex,
        short startFrom,
        boolean isFullInit,
        boolean isDebug,
        boolean isTrace,
        OptimizedTmpGraph tmpGraph,
        NodeTask[] tasks,
        String threadName,
        String logSuffix
    ) {
        var dsOffsets = draw.getDownstreamOffsets();
        var dsEdges = draw.getDownstreamEdges();
        var dsStart = dsOffsets[targetIndex];
        var dsEnd = dsOffsets[targetIndex + 1];

        // println("[" + threadName + "] Node " + targetIndex + " notifyDownstreamConsumers enter, dsStart=" + dsStart + ", dsEnd=" + dsEnd);

        if (dsStart == dsEnd) {
            // println("[" + threadName + "] Node " + targetIndex + " has no downstream consumers");
            return;
        }

        var notified = new BitSet(draw.size());

        for (var k = dsStart; k < dsEnd; k++) {
            var nextIdx = dsEdges[k];
            var nextTask = tasks[nextIdx];
            if (nextTask == null) {
                // println("[" + threadName + "] Node " + targetIndex + " found null task for downstream index " + nextIdx + ", skipping");
                continue;
            }

            if (notified.get(nextIdx)) {
                // println("[" + threadName + "] Node " + targetIndex + " already notified downstream index " + nextIdx + " in this pass, skipping duplicate edge");
                continue;
            }
            notified.set(nextIdx);

            var remaining = nextTask.remainingDeps.decrementAndGet();
            // println("[" + threadName + "] Node " + targetIndex + logSuffix + " decremented Node " + nextIdx + " remainingDeps -> " + remaining);

            if (remaining == 0 && !nextTask.isCancelled) {
                // println("[" + threadName + "] Node " + nextIdx + " remainingDeps hit 0, spawning virtual thread");
                Thread.ofVirtual().start(() -> executeNodeTask(nextTask, startFrom, isFullInit, isDebug, isTrace, tmpGraph, tasks));
            }
        }
        // println("[" + threadName + "] Node " + targetIndex + " notifyDownstreamConsumers exit");
    }

    private void decrementGlobalTasks(int count, String threadName, short nodeIdx, String statusSuffix) {
        var globalLeft = (int) GLOBAL_REMAINING_VH.getAndAdd(this, -count) - count;
        // println("[" + threadName + "] Node " + nodeIdx + statusSuffix + ". globalRemainingTasks -> " + globalLeft);
        if (globalLeft == 0) {
            // println("[" + threadName + "] Node " + nodeIdx + statusSuffix + " unparking mainThread");
            LockSupport.unpark(mainThread);
        }
    }

    @SuppressWarnings("unchecked")
    private void releaseAllInternal() {
        var size = draw.size();
        Throwable error = null;

        for (var i = (short) (size - 1); i >= 0; i--) {
            try {
                var object = OBJECTS_VH.getVolatile(graphObjects, i);
                if (object == null || object instanceof ConditionFailedValue) continue;

                var interOffsets = draw.getInterceptorOffsets();
                var interEdges = draw.getInterceptorEdges();
                var start = interOffsets[i];
                var end = interOffsets[i + 1];

                for (var j = (short) (end - 1); j >= start; j--) {
                    var interceptorIndex = interEdges[j];
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

    private static final class NodeTask {

        final AtomicInteger started = new AtomicInteger(0);
        final AtomicInteger remainingDeps;
        final short index;
        volatile boolean isCancelled = false;

        NodeTask(short index, int initialDeps) {
            this.index = index;
            this.remainingDeps = new AtomicInteger(initialDeps);
        }
    }

    static final class ConditionFailedValue {
        final String reason;

        ConditionFailedValue(String reason) {
            this.reason = reason;
        }
    }
}
