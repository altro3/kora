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
        return Duration.ofNanos(500_000);
    }

    default Duration fifoSwitchTime() {
        return Duration.ofNanos(250_000);
    }

    default Duration taskFifoThreshold() {
        return Duration.ofNanos(250_000);
    }

    default Duration blockTime() {
        return Duration.ofNanos(1_000_000);
    }

    default int throughputModeThreshold() {
        return 8;
    }

    default int workSpillThreshold() {
        return 32;
    }

    default Duration workSpillMinQueueAge() {
        return Duration.ofNanos(1_000_0000);
    }

    default int normalWarmupTasks() {
        return 0;
    }
}
