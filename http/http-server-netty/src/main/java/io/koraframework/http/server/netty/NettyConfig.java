package io.koraframework.http.server.netty;

import io.koraframework.config.common.annotation.ConfigMapper;

import java.time.Duration;

@ConfigMapper
public interface NettyConfig {

    int DEFAULT_IO_THREADS = Math.max(Runtime.getRuntime().availableProcessors(), 2);
    Duration DEFAULT_KEEP_ALIVE_TIMEOUT = Duration.ofSeconds(60);

    default int ioThreads() {
        return DEFAULT_IO_THREADS;
    }

    default Duration threadKeepAliveTimeout() {
        return DEFAULT_KEEP_ALIVE_TIMEOUT;
    }
}
