package io.koraframework.netty.common.loom;

import io.netty.channel.*;
import io.netty.util.*;
import io.netty.util.concurrent.*;
import io.netty.util.internal.*;
import jdk.jfr.*;
import org.jctools.queues.*;
import org.jspecify.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
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
        Runner runner = new Runner(runners.size(), (Factory) args[0], ioHandlerFactory);
        this.runners.add(runner);
        executor.execute(runner);
        return runner.delegate;
    }

    private static Thread unstartedVirtualThread(String name, Consumer<Thread.Builder.OfVirtual> builderModifier, Runnable task) {
        Thread.Builder.OfVirtual builder = Thread.ofVirtual().name(name);
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
        final int id;
        final Factory factory;
        final ManualIoEventLoop delegate;
        final AttributeMap attributeMap = new DefaultAttributeMap();
        IoHandler backingHandler;
        Thread carrier;
        Runnable ioContinuation;
        Thread ioThread;
        volatile boolean ioContinuationScheduled;
        final Queue<Runnable> globalLoomQueue = new MpscUnboundedArrayQueue<>(4096);
        final Deque<ScheduledTask> localLoomQueue = new ArrayDeque<>();
        volatile boolean loomNested;
        volatile boolean expediteWrite = false;
        volatile boolean block;
        boolean continuationsFifo = false;
        long continuationTime = 0L;
        boolean throughputMode = false;
        boolean idle = false;
        volatile int activeThreadsLocal = 0;
        final AtomicInteger activeThreadsExternal = new AtomicInteger();
        volatile boolean drained = false;
        final AtomicInteger enqueuing = new AtomicInteger();

        int warmupTasks;

        Runner(int id, Factory factory, IoHandlerFactory ioHandlerFactory) {
            this.id = id;
            this.factory = factory;
            this.warmupTasks = factory.configuration.normalWarmupTasks();
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
            Object scheduler;
            if (LoomBranchSupport.isSupported()) {
                assert thread == Thread.currentThread();
                scheduler = LoomBranchSupport.currentScheduler();
            } else {
                scheduler = PrivateLoomSupport.getScheduler(thread);
            }
            if (!ownsScheduler(scheduler)) {
                return false;
            }
            return !PrivateLoomSupport.isCarrierThreadSupported() || PrivateLoomSupport.getCarrierThread(thread) == carrier;
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
            return activeThreadsLocal + activeThreadsExternal.get();
        }

        private boolean isFallingBehind() {
            long minAge = factory.configuration.workSpillMinQueueAge().toNanos();
            if (minAge <= 0 || !PrivateLoomSupport.isCarrierThreadSupported() || !isOnRunner(Thread.currentThread())) {
                return true;
            }
            ScheduledTask oldest = localLoomQueue.peekLast();
            return oldest != null && System.nanoTime() - oldest.scheduleTime() >= minAge;
        }

        @Override
        public Thread newThread(Runnable r) {
            return unstartedVirtualThread("loom-on-netty-" + id + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong()), b -> {
                if (!LoomBranchSupport.isSupported() && !PrivateLoomSupport.isSupported()) {
                    return;
                }
                if (warmupTasks > 0) {
                    warmupTasks--;
                    if (!LoomBranchSupport.isSupported()) {
                        PrivateLoomSupport.setScheduler(b, PrivateLoomSupport.getDefaultScheduler());
                    }
                    return;
                }

                Runner dst = Runner.this;
                int active = activeThreads();
                if (active >= factory.configuration.workSpillThreshold() && isFallingBehind()) {
                    // spill to a less busy event loop
                    for (Runner runner : runners) {
                        int a = runner.activeThreads();
                        if (a < active) {
                            dst = runner;
                            active = a;
                        }
                    }
                }
                if (LoomBranchSupport.isSupported()) {
                    LoomBranchSupport.setScheduler(b, new StickyScheduler(dst));
                } else {
                    PrivateLoomSupport.setScheduler(b, new StickyScheduler(dst));
                }
            }, r);
        }

        @Override
        public void run() {
            carrier = Thread.currentThread();

            ioThread = unstartedVirtualThread(
                "loom-on-netty-" + id + "-io",
                b -> {
                    if (LoomBranchSupport.isSupported()) {
                        LoomBranchSupport.setScheduler(b, new IoScheduler(this));
                    } else if (PrivateLoomSupport.isSupported()) {
                        PrivateLoomSupport.setScheduler(b, new IoScheduler(this));
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
                throughputMode = localLoomQueue.size() > factory.configuration.throughputModeThreshold();
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
                runOnDefaultScheduler(localLoomQueue.pollLast().task());
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
                    waitNanos = factory.configuration.blockTime().toNanos();
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
            while (true) {
                Runnable task = globalLoomQueue.poll();
                if (task == null) {
                    break;
                }
                localLoomQueue.addFirst(new ScheduledTask(System.nanoTime(), task));
            }
        }

        private long timeSlice() {
            return (throughputMode ? factory.configuration.timeSliceThroughput() : factory.configuration.timeSliceLatency()).toNanos();
        }

        private boolean runContinuations(@Nullable Runnable immediateTask, long deadline) {
            assert !loomNested;

            boolean ranAny = false;
            loomNested = true;
            long now;
            do {
                now = System.nanoTime();
                // select a task
                Runnable task;
                if (immediateTask == null) {
                    if (localLoomQueue.isEmpty()) {
                        break;
                    }
                    if (continuationsFifo) {
                        task = localLoomQueue.pollLast().task();
                    } else {
                        task = localLoomQueue.pollFirst().task();
                    }
                } else {
                    task = immediateTask;
                    immediateTask = null;
                }
                ranAny = true;
                task.run();
                activeThreadsLocal--;
                long end = System.nanoTime();
                continuationTime += end - now;
                now = end;

                if (continuationTime > factory.configuration.fifoSwitchTime().toNanos()) {
                    if (continuationsFifo) {
                        continuationsFifo = false;
                    } else {
                        ScheduledTask last = localLoomQueue.peekLast();
                        continuationsFifo = last != null && now - last.scheduleTime > factory.configuration.taskFifoThreshold().toNanos();
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

            // JFR
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

            if (Thread.currentThread() == carrier) {
                activeThreadsLocal++;
                long time = System.nanoTime();
                if (idle && !loomNested && !expediteWrite) {
                    if (scheduled != null) {
                        scheduled.scheduleMode = 2;
                        scheduled.queueDepth = -1;
                        scheduled.commit();
                    }
                    runContinuations(command, time + factory.configuration.timeSliceLatency().toNanos());
                } else {
                    if (scheduled != null) {
                        scheduled.scheduleMode = 1;
                        scheduled.queueDepth = localLoomQueue.size();
                        scheduled.commit();
                    }
                    localLoomQueue.addFirst(new ScheduledTask(time, command));
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

                if (isOnRunner(Thread.currentThread())) {
                    if (!throughputMode && !expediteWrite) {
                        Thread.yield();
                    }
                } else {
                    backingHandler.wakeup();
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
        @Override
        public void execute(Runnable command) {
            Thread currentThread = Thread.currentThread();
            Executor dst;
            if (currentThread instanceof ForkJoinWorkerThread fjwt && fjwt.getPool() == PrivateLoomSupport.getDefaultScheduler()) {
                dst = PrivateLoomSupport.getDefaultScheduler();
            } else if (currentThread.isVirtual() && PrivateLoomSupport.getScheduler(currentThread) == PrivateLoomSupport.getDefaultScheduler()) {
                dst = PrivateLoomSupport.getDefaultScheduler();
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
                    dst = (t, r) -> LoomBranchSupport.runOnDefaultScheduler(r);
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

    private record ScheduledTask(
        long scheduleTime,
        Runnable task
    ) {
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
