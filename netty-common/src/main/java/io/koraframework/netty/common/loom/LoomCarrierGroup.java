package io.koraframework.netty.common.loom;

import io.netty.channel.*;
import io.netty.util.*;
import io.netty.util.concurrent.*;
import io.netty.util.internal.*;
import jdk.jfr.*;
import org.jctools.queues.*;
import org.jspecify.annotations.*;

import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

public final class LoomCarrierGroup extends MultiThreadIoEventLoopGroup {
    private static boolean jfrRecorderSeen;

    List<Runner> runners;

    private LoomCarrierGroup(Factory factory, int nThreads, Executor executor, IoHandlerFactory ioHandlerFactory) {
        super(nThreads, executor, ioHandlerFactory, factory);
    }

    @Override
    protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory, Object... args) {
        if (runners == null) {
            runners = new ArrayList<>();
        }
        var runner = new Runner(runners.size(), (Factory) args[0], ioHandlerFactory);
        this.runners.add(runner);
        executor.execute(runner);
        return runner.delegate;
    }

    private static Thread unstartedVirtualThread(String name, Consumer<Thread.Builder.OfVirtual> builderModifier, Runnable task) {
        var builder = Thread.ofVirtual().name(name);
        builderModifier.accept(builder);
        return builder.unstarted(task);
    }

    private static boolean isJfrRecorderInitialized() {
        if (jfrRecorderSeen) {
            return true;
        }
        if (FlightRecorder.isInitialized()) {
            jfrRecorderSeen = true;
            return true;
        }
        return false;
    }

    public static final class Factory {
        final EventLoopLoomFactory holder;
        final LoomCarrierConfig configuration;

        Factory(EventLoopLoomFactory holder, LoomCarrierConfig configuration) {
            this.holder = holder;
            this.configuration = configuration;
        }

        public EventLoopGroup create(int nThreads, Executor executor, IoHandlerFactory ioHandlerFactory) {
            return new LoomCarrierGroup(this, nThreads, executor, ioHandlerFactory);
        }
    }

    final class Runner implements Runnable, EventLoopVirtualThreadScheduler, ThreadFactory, Executor, LoomBranchSupport.VirtualThreadSchedulerProxy {

        private static final VarHandle ACTIVE_THREADS_LOCAL;

        static {
            try {
                ACTIVE_THREADS_LOCAL = MethodHandles.lookup()
                    .findVarHandle(Runner.class, "activeThreadsLocal", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final int id;
        final Factory factory;
        final ManualIoEventLoop delegate;
        final StickyScheduler stickyScheduler = new StickyScheduler(this);
        final IoScheduler ioScheduler = new IoScheduler(this);
        final AttributeMap attributeMap = new DefaultAttributeMap();

        final String workerThreadPrefix;
        final long timeSliceLatencyNanos;
        final long timeSliceThroughputNanos;
        final long fifoSwitchTimeNanos;
        final long taskFifoThresholdNanos;
        final long blockTimeNanos;
        final int throughputModeThreshold;
        final int workSpillThreshold;
        final long workSpillMinQueueAgeNanos;

        IoHandler backingHandler;
        Thread carrier;
        Runnable ioContinuation;
        Thread ioThread;
        volatile boolean ioContinuationScheduled;
        final Queue<Runnable> globalLoomQueue = new MpscUnboundedArrayQueue<>(4096);
        final LocalTaskQueue localLoomQueue = new LocalTaskQueue(256);
        volatile boolean loomNested;
        volatile boolean expediteWrite = false;
        volatile boolean block;
        boolean continuationsFifo = false;
        long continuationTime = 0L;
        boolean throughputMode = false;
        volatile boolean idle = false;
        int activeThreadsLocal = 0;
        final AtomicInteger activeThreadsExternal = new AtomicInteger();
        volatile boolean drained = false;
        final AtomicInteger enqueuing = new AtomicInteger();

        int warmupTasks;
        long threadSeq;

        Runner(int id, Factory factory, IoHandlerFactory ioHandlerFactory) {
            this.id = id;
            this.factory = factory;
            var cfg = factory.configuration;
            this.workerThreadPrefix = "loom-on-netty-" + id + "-";
            this.timeSliceLatencyNanos = cfg.timeSliceLatency().toNanos();
            this.timeSliceThroughputNanos = cfg.timeSliceThroughput().toNanos();
            this.fifoSwitchTimeNanos = cfg.fifoSwitchTime().toNanos();
            this.taskFifoThresholdNanos = cfg.taskFifoThreshold().toNanos();
            this.blockTimeNanos = cfg.blockTime().toNanos();
            this.throughputModeThreshold = cfg.throughputModeThreshold();
            this.workSpillThreshold = cfg.workSpillThreshold();
            this.workSpillMinQueueAgeNanos = cfg.workSpillMinQueueAge().toNanos();
            this.warmupTasks = cfg.normalWarmupTasks();

            IoHandlerFactory proxied = ioExecutor -> {
                backingHandler = ioHandlerFactory.newHandler(ioExecutor);
                return new DelegateIoHandler(backingHandler) {
                    @Override
                    public void wakeup() {
                        if (block || delegate.isShuttingDown()) {
                            block = false;
                            super.wakeup();
                        }
                        Thread thread = Thread.currentThread();
                        if (isOnRunner(thread)) {
                            if (!throughputMode) {
                                expediteWrite = true;
                                Thread.yield();
                            }
                        }
                    }
                };
            };
            this.delegate = new ManualIoEventLoop(null, proxied);
        }

        @Override
        public AttributeMap attributeMap() {
            return attributeMap;
        }

        @Override
        public EventExecutor eventLoop() {
            return delegate;
        }

        boolean isOnRunner(Thread thread) {
            if (!thread.isVirtual()) {
                return false;
            }
            if (PrivateLoomSupport.isCarrierThreadSupported() && PrivateLoomSupport.getCarrierThread(thread) != carrier) {
                return false;
            }
            Object scheduler;
            if (LoomBranchSupport.isSupported()) {
                assert thread == Thread.currentThread();
                scheduler = LoomBranchSupport.currentScheduler();
            } else {
                scheduler = PrivateLoomSupport.getScheduler(thread);
            }
            return ownsScheduler(scheduler);
        }

        private boolean ownsScheduler(Object scheduler) {
            if (scheduler == this) {
                return true;
            } else if (scheduler instanceof IoScheduler s) {
                return s.runner == this;
            } else if (scheduler instanceof StickyScheduler s) {
                return s.io == this;
            } else {
                return false;
            }
        }

        int activeThreads() {
            return (int) ACTIVE_THREADS_LOCAL.getOpaque(this) + activeThreadsExternal.get();
        }

        private boolean isFallingBehind() {
            if (workSpillMinQueueAgeNanos <= 0 || !PrivateLoomSupport.isCarrierThreadSupported() || !isOnRunner(Thread.currentThread())) {
                return true;
            }
            long oldestTime = localLoomQueue.peekLastTime();
            return oldestTime > 0L && System.nanoTime() - oldestTime >= workSpillMinQueueAgeNanos;
        }

        @Override
        public Thread newThread(Runnable r) {
            String name = workerThreadPrefix + (++threadSeq);
            return unstartedVirtualThread(name, b -> {
                boolean branchSupported = LoomBranchSupport.isSupported();
                if (!branchSupported && !PrivateLoomSupport.isSupported()) {
                    return;
                }
                if (warmupTasks > 0) {
                    warmupTasks--;
                    if (!branchSupported) {
                        PrivateLoomSupport.setScheduler(b, PrivateLoomSupport.getDefaultScheduler());
                    }
                    return;
                }

                Runner dst = Runner.this;
                int active = activeThreads();
                if (active >= workSpillThreshold && isFallingBehind()) {
                    for (Runner runner : runners) {
                        int a = runner.activeThreads();
                        if (a < active) {
                            dst = runner;
                            active = a;
                        }
                    }
                }
                if (branchSupported) {
                    LoomBranchSupport.setScheduler(b, dst.stickyScheduler);
                } else {
                    PrivateLoomSupport.setScheduler(b, dst.stickyScheduler);
                }
            }, r);
        }

        @Override
        public void run() {
            carrier = Thread.currentThread();

            ioThread = unstartedVirtualThread(
                workerThreadPrefix + "io",
                b -> {
                    if (LoomBranchSupport.isSupported()) {
                        LoomBranchSupport.setScheduler(b, ioScheduler);
                    } else if (PrivateLoomSupport.isSupported()) {
                        PrivateLoomSupport.setScheduler(b, ioScheduler);
                    }
                },
                () -> FastThreadLocalThread.runWithFastThreadLocal(this::runIo)
            );
            ioThread.start();
            assert ioContinuationScheduled;

            while (!delegate.isTerminated()) {
                boolean ioContinuationScheduled = this.ioContinuationScheduled;
                if (!ioContinuationScheduled && localLoomQueue.isEmpty() && globalLoomQueue.isEmpty()) {
                    LockSupport.park();
                    ioContinuationScheduled = this.ioContinuationScheduled;
                }
                if (ioContinuationScheduled) {
                    this.ioContinuationScheduled = false;
                    ioContinuation.run();
                }
                tick(3);
                globalToLocal();
                throughputMode = localLoomQueue.size() > throughputModeThreshold;
                if (runContinuations(null, System.nanoTime() + timeSlice()) || expediteWrite) {
                    block = false;
                }
            }
            drained = true;
            while (ioThread.isAlive()) {
                if (ioContinuationScheduled) {
                    ioContinuationScheduled = false;
                    ioContinuation.run();
                } else {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }
            while (enqueuing.get() != 0) {
                Thread.onSpinWait();
            }
            globalToLocal();
            while (!localLoomQueue.isEmpty()) {
                runOnDefaultScheduler(localLoomQueue.pollLast());
            }
        }

        private static void runOnDefaultScheduler(Runnable command) {
            if (LoomBranchSupport.isSupported()) {
                LoomBranchSupport.runOnDefaultScheduler(command);
            } else {
                PrivateLoomSupport.getDefaultScheduler().execute(command);
            }
        }

        private void runIo() {
            delegate.setOwningThread(Thread.currentThread());
            ThreadExecutorMap.setCurrentExecutor(delegate);
            factory.holder.targetScheduler.set(this);

            while (!delegate.isShuttingDown()) {
                long waitNanos;
                if (block) {
                    waitNanos = blockTimeNanos;
                    idle = true;
                    tick(1);
                } else {
                    waitNanos = -1;
                    tick(2);
                }
                block = delegate.run(waitNanos, timeSlice()) == 0;
                idle = false;
                expediteWrite = false;
                Thread.yield();
                tick(4);
                if (delegate.runNonBlockingTasks(timeSlice()) != 0) {
                    block = false;
                }
            }

            while (!delegate.isTerminated()) {
                delegate.runNow(TimeUnit.MILLISECONDS.toNanos(10));
                Thread.yield();
            }

        }

        private void globalToLocal() {
            int moved = 0;
            long now = 0L;
            while (true) {
                Runnable task = globalLoomQueue.poll();
                if (task == null) {
                    break;
                }
                if (moved == 0) {
                    now = System.nanoTime();
                }
                moved++;
                localLoomQueue.addFirst(now, task);
            }
            if (moved > 0) {
                activeThreadsExternal.addAndGet(-moved);
                ACTIVE_THREADS_LOCAL.setRelease(this, activeThreadsLocal + moved);
            }
        }

        private long timeSlice() {
            return throughputMode ? timeSliceThroughputNanos : timeSliceLatencyNanos;
        }

        private boolean runContinuations(@Nullable Runnable immediateTask, long deadline) {
            assert !loomNested;

            boolean ranAny = false;
            loomNested = true;
            long now = System.nanoTime();
            do {
                Runnable task;
                if (immediateTask == null) {
                    if (localLoomQueue.isEmpty()) {
                        break;
                    }
                    task = continuationsFifo ? localLoomQueue.pollLast() : localLoomQueue.pollFirst();
                } else {
                    task = immediateTask;
                    immediateTask = null;
                }
                ranAny = true;
                task.run();
                ACTIVE_THREADS_LOCAL.setRelease(this, activeThreadsLocal - 1);
                long end = System.nanoTime();
                continuationTime += end - now;
                now = end;

                if (continuationTime > fifoSwitchTimeNanos) {
                    if (continuationsFifo) {
                        continuationsFifo = false;
                    } else {
                        long oldestTime = localLoomQueue.peekLastTime();
                        continuationsFifo = oldestTime > 0L && now - oldestTime > taskFifoThresholdNanos;
                    }
                    continuationTime = 0;
                }
            } while (now < deadline && !expediteWrite);
            loomNested = false;
            return ranAny;
        }

        private void executeIo(Thread thread, Runnable command) {
            if (thread == ioThread && !drained) {
                Thread t = Thread.currentThread();
                ioContinuation = command;
                ioContinuationScheduled = true;
                if (t != carrier && !isOnRunner(t)) {
                    LockSupport.unpark(carrier);
                }
            } else {
                runOnDefaultScheduler(command);
            }
        }

        private void executeIo(Runnable command) {
            if (!drained) {
                Runnable ioContinuation = this.ioContinuation;
                if (ioContinuation == null) {
                    ioContinuation = command;
                    this.ioContinuation = command;
                }
                if (ioContinuation == command) {
                    Thread t = Thread.currentThread();
                    ioContinuationScheduled = true;
                    if (t != carrier && !isOnRunner(t)) {
                        LockSupport.unpark(carrier);
                    }
                    return;
                }
            }
            runOnDefaultScheduler(command);
        }

        @Override
        public void execute(Runnable task) {
            execute(null, task);
        }

        @Override
        public void execute(Thread thread, Runnable command) {
            if (delegate.isShuttingDown()) {
                runOnDefaultScheduler(command);
                return;
            }

            ContinuationScheduled scheduled;
            if (isJfrRecorderInitialized() && ContinuationScheduled.INSTANCE.isEnabled()) {
                scheduled = new ContinuationScheduled();
                long hash = System.identityHashCode(command);
                scheduled.hashCode = hash;
                scheduled.virtualThreadName = thread == null ? null : thread.getName();

                Runnable r = command;
                command = () -> {
                    ContinuationStarted started = new ContinuationStarted();
                    started.begin();

                    r.run();

                    started.end();
                    started.hashCode = hash;
                    started.virtualThreadName = thread == null ? null : thread.getName();
                    started.commit();
                };
            } else {
                scheduled = null;
            }

            var curThread = Thread.currentThread();
            if (curThread == carrier) {
                ACTIVE_THREADS_LOCAL.setRelease(this, activeThreadsLocal + 1);
                if (idle && !loomNested && !expediteWrite) {
                    long time = System.nanoTime();
                    if (scheduled != null) {
                        scheduled.scheduleMode = 2;
                        scheduled.queueDepth = -1;
                        scheduled.commit();
                    }
                    runContinuations(command, time + timeSliceLatencyNanos);
                } else {
                    if (scheduled != null) {
                        scheduled.scheduleMode = 1;
                        scheduled.queueDepth = localLoomQueue.size();
                        scheduled.commit();
                    }
                    long schedTime = localLoomQueue.isEmpty() ? 0L : System.nanoTime();
                    localLoomQueue.addFirst(schedTime, command);
                }
            } else {
                activeThreadsExternal.incrementAndGet();
                if (scheduled != null) {
                    scheduled.scheduleMode = 3;
                    scheduled.queueDepth = globalLoomQueue.size();
                    scheduled.commit();
                }
                if (!enqueueExternal(command)) {
                    activeThreadsExternal.decrementAndGet();
                    return;
                }

                if (isOnRunner(curThread)) {
                    if (!throughputMode && !expediteWrite) {
                        Thread.yield();
                    }
                } else {
                    if (idle || block) {
                        backingHandler.wakeup();
                    }
                    LockSupport.unpark(carrier);
                }
            }
        }

        boolean enqueueExternal(Runnable command) {
            enqueuing.incrementAndGet();
            try {
                if (drained) {
                    runOnDefaultScheduler(command);
                    return false;
                }
                globalLoomQueue.add(command);
                return true;
            } finally {
                enqueuing.decrementAndGet();
            }
        }

        private void tick(int type) {
            if (isJfrRecorderInitialized() && LoopTick.INSTANCE.isEnabled()) {
                LoopTick tick = new LoopTick();
                tick.loopIndex = id;
                tick.type = type;
                tick.activeThreads = activeThreads();
                tick.commit();
            }
        }
    }

    static final class LocalTaskQueue {
        private Runnable[] tasks;
        private long[] times;
        private int head;
        private int tail;
        private int mask;

        LocalTaskQueue(int initialCapacity) {
            int cap = Integer.highestOneBit(initialCapacity - 1) << 1;
            this.tasks = new Runnable[cap];
            this.times = new long[cap];
            this.mask = cap - 1;
        }

        boolean isEmpty() {
            return head == tail;
        }

        int size() {
            return (tail - head) & mask;
        }

        void addFirst(long time, Runnable task) {
            head = (head - 1) & mask;
            tasks[head] = task;
            times[head] = time;
            if (head == tail) {
                grow();
            }
        }

        Runnable pollFirst() {
            int h = head;
            Runnable task = tasks[h];
            tasks[h] = null;
            head = (h + 1) & mask;
            return task;
        }

        Runnable pollLast() {
            int t = (tail - 1) & mask;
            Runnable task = tasks[t];
            tasks[t] = null;
            tail = t;
            return task;
        }

        long peekLastTime() {
            if (head == tail) {
                return 0L;
            }
            return times[(tail - 1) & mask];
        }

        private void grow() {
            int oldCap = tasks.length;
            int newCap = oldCap << 1;
            Runnable[] newTasks = new Runnable[newCap];
            long[] newTimes = new long[newCap];
            int rightCount = oldCap - head;
            System.arraycopy(tasks, head, newTasks, 0, rightCount);
            System.arraycopy(tasks, 0, newTasks, rightCount, head);
            System.arraycopy(times, head, newTimes, 0, rightCount);
            System.arraycopy(times, 0, newTimes, rightCount, head);
            tasks = newTasks;
            times = newTimes;
            head = 0;
            tail = oldCap;
            mask = newCap - 1;
        }
    }

    record IoScheduler(Runner runner) implements Executor, EventLoopVirtualThreadScheduler, LoomBranchSupport.VirtualThreadSchedulerProxy {
        @Override
        public AttributeMap attributeMap() {
            return runner.attributeMap();
        }

        @Override
        public EventExecutor eventLoop() {
            return runner.eventLoop();
        }

        @Override
        public void execute(Thread thread, Runnable task) {
            runner.executeIo(thread, task);
        }

        @Override
        public void execute(Runnable command) {
            runner.executeIo(command);
        }
    }

    record StickyScheduler(Runner io) implements Executor, EventLoopVirtualThreadScheduler, LoomBranchSupport.VirtualThreadSchedulerProxy {
        private static final LoomBranchSupport.VirtualThreadSchedulerProxy DEFAULT_BRANCH_PROXY =
            (t, r) -> LoomBranchSupport.runOnDefaultScheduler(r);

        @Override
        public void execute(Runnable command) {
            Thread currentThread = Thread.currentThread();
            var defaultScheduler = PrivateLoomSupport.getDefaultScheduler();
            Executor dst;
            if (currentThread instanceof ForkJoinWorkerThread fjwt && fjwt.getPool() == defaultScheduler) {
                dst = defaultScheduler;
            } else if (currentThread.isVirtual() && PrivateLoomSupport.getScheduler(currentThread) == defaultScheduler) {
                dst = defaultScheduler;
            } else {
                dst = io;
            }
            dst.execute(command);
        }

        @Override
        public void execute(Thread thread, Runnable task) {
            LoomBranchSupport.VirtualThreadSchedulerProxy dst;
            if (Thread.currentThread().isVirtual()) {
                dst = LoomBranchSupport.currentScheduler();
                if (dst instanceof EventLoopVirtualThreadScheduler) {
                    if (dst instanceof IoScheduler s) {
                        dst = s.runner;
                    } else if (dst instanceof StickyScheduler s) {
                        dst = s.io;
                    }
                } else {
                    dst = DEFAULT_BRANCH_PROXY;
                }
            } else {
                dst = io;
            }
            dst.execute(thread, task);
        }

        @Override
        public AttributeMap attributeMap() {
            return io.attributeMap();
        }

        @Override
        public EventExecutor eventLoop() {
            return io.eventLoop();
        }
    }

    @StackTrace(false)
    @Enabled(false)
    static class ContinuationScheduled extends Event {
        static final ContinuationScheduled INSTANCE = new ContinuationScheduled();

        long hashCode;
        String virtualThreadName;
        int scheduleMode;
        int queueDepth;
    }

    @StackTrace(false)
    @Enabled(false)
    static class ContinuationStarted extends Event {
        long hashCode;
        String virtualThreadName;
    }

    @StackTrace(false)
    @Enabled(false)
    static class LoopTick extends Event {
        static final LoopTick INSTANCE = new LoopTick();

        int loopIndex;
        int type;
        int activeThreads;
    }
}
