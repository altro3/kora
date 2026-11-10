package io.koraframework.http.server.netty.loom;

import io.koraframework.config.common.annotation.*;

import java.time.*;

@ConfigMapper
public interface LoomCarrierConfig {

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
        return 10;
    }

    default int workSpillThreshold() {
        return 2;
    }

    default Duration workSpillMinQueueAge() {
        return Duration.ofMillis(5);
    }

    default int normalWarmupTasks() {
        return 100;
    }
}
