package io.koraframework.netty.common.loom;

import io.koraframework.config.common.annotation.*;

import java.time.*;

@ConfigMapper
public interface LoomCarrierConfig {

    default boolean enabled() {
        return true;
    }

    default Duration timeSliceLatency() {
        return Duration.ofNanos(500_000);
    }

    default Duration timeSliceThroughput() {
        return Duration.ofMillis(5);
    }

    default Duration fifoSwitchTime() {
        return Duration.ofMillis(1);
    }

    default Duration taskFifoThreshold() {
        return Duration.ofMillis(5);
    }

    default Duration blockTime() {
        return Duration.ofSeconds(1);
    }

    default int throughputModeThreshold() {
        return 24;
    }

    default int workSpillThreshold() {
        return 32;
    }

    default Duration workSpillMinQueueAge() {
        return Duration.ofNanos(500);
    }

    default int normalWarmupTasks() {
        return 16;
    }
}
