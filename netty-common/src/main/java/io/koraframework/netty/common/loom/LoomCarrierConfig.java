package io.koraframework.netty.common.loom;

import java.time.*;

public interface LoomCarrierConfig {

    default boolean enabled() {
        return true;
    }

    default Duration timeSliceLatency() {
        return Duration.ofNanos(500_000);
    }

    default Duration timeSliceThroughput() {
        return Duration.ofMillis(1);
    }

    default Duration fifoSwitchTime() {
        return Duration.ofNanos(250_000);
    }

    default Duration taskFifoThreshold() {
        return Duration.ofNanos(250_000);
    }

    default Duration blockTime() {
        return Duration.ofMillis(1);
    }

    default int throughputModeThreshold() {
        return 12;
    }

    default int workSpillThreshold() {
        return 8;
    }

    default Duration workSpillMinQueueAge() {
        return Duration.ofMillis(1);
    }

    default int normalWarmupTasks() {
        return 0;
    }
}
