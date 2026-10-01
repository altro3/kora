package io.koraframework.http.server.netty;

import io.koraframework.config.common.annotation.ConfigMapper;

import java.time.Duration;

@ConfigMapper
public interface NettyConfig {

    default int ioThreads() {
        return Math.max(Runtime.getRuntime().availableProcessors(), 2);
    }

    default Duration threadKeepAliveTimeout() {
        return Duration.ofSeconds(60);
    }
}
