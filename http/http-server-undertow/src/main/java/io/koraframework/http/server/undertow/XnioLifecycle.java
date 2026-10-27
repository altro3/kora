package io.koraframework.http.server.undertow;

import io.koraframework.common.Configurer;
import io.undertow.Undertow;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import io.koraframework.application.graph.Lifecycle;
import io.koraframework.application.graph.ValueOf;
import io.koraframework.application.graph.Wrapped;
import io.koraframework.common.util.TimeUtils;

import java.util.concurrent.CompletableFuture;

public final class XnioLifecycle implements Lifecycle, Wrapped<XnioWorker> {

    private static final Logger logger = LoggerFactory.getLogger(XnioLifecycle.class);

    private final ValueOf<UndertowConfig> configValue;
    @Nullable
    private final Configurer<XnioWorker.Builder> configurer;

    private volatile XnioWorker worker;

    public XnioLifecycle(ValueOf<UndertowConfig> configValue,
                         @Nullable Configurer<XnioWorker.Builder> configurer) {
        this.configValue = configValue;
        this.configurer = configurer;
    }

    @Override
    public void init() throws Exception {
        logger.debug("XnioWorker starting...");
        var started = System.nanoTime();

        var httpServerConfig = configValue.get();

        // the worker is built on a platform thread on purpose: init runs on a virtual thread, and XNIO
        // does blocking work while building. Holding the JVM alive is not this module's job -- a single
        // non-daemon thread in KoraApplication#run does that for the whole application.
        var f = new CompletableFuture<XnioWorker>();
        var t = Thread.ofPlatform()
            .name("kora-undertow-init")
            .daemon(false)
            .unstarted(new XnioWorkerInitRunnable(httpServerConfig, configurer, f));
        t.start();
        worker = f.get();

        logger.info("XnioWorker started in {}", TimeUtils.tookForLogging(started));
    }

    @Override
    public void release() throws Exception {
        if (worker != null) {
            logger.debug("XnioWorker stopping...");
            var started = System.nanoTime();

            worker.shutdown();
            worker.awaitTermination();

            logger.info("XnioWorker stopped in {}", TimeUtils.tookForLogging(started));
        }
    }

    @Override
    public XnioWorker value() {
        return worker;
    }

    private static final class XnioWorkerInitRunnable implements Runnable {
        private final UndertowConfig httpServerConfig;
        @Nullable
        private final Configurer<XnioWorker.Builder> configurer;
        private final CompletableFuture<XnioWorker> future;

        public XnioWorkerInitRunnable(UndertowConfig httpServerConfig,
                                      @Nullable Configurer<XnioWorker.Builder> configurer,
                                      CompletableFuture<XnioWorker> future) {
            this.httpServerConfig = httpServerConfig;
            this.configurer = configurer;
            this.future = future;
        }

        @Override
        public void run() {
            try {
                // XnioWorker will be daemon despite flag .setDaemon(false) if the thread it is started from is daemon (virtual thread)
                var builder = Xnio.getInstance(Undertow.class.getClassLoader())
                    .createWorkerBuilder()
                    .setCoreWorkerPoolSize(1)
                    .setMaxWorkerPoolSize(1)
                    .setWorkerIoThreads(httpServerConfig.ioThreads())
                    .setWorkerKeepAlive(((int) httpServerConfig.threadKeepAliveTimeout().toMillis()))
                    .setDaemon(false)
                    .setWorkerName("kora-undertow");

                if (configurer != null) {
                    builder = configurer.configure(builder);
                }
                var createdWorker = builder.build();

                future.complete(createdWorker);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }
    }
}
