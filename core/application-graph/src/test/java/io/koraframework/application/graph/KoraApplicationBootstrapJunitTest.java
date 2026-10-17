package io.koraframework.application.graph;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import static java.lang.IO.println;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KoraApplicationBootstrapJunitTest {

    private static final int ITERATIONS = 100;

    @Test
    public void testBootstrapWithoutUptime() throws Exception {
        long totalDurationNanos = 0;
        String lastOutput;

        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            lastOutput = runIsolated(false);
            long end = System.nanoTime();
            totalDurationNanos += (end - start);

            try {
                assertTrue(lastOutput.contains("Application initialized in"));
                assertFalse(lastOutput.contains("JVM running for"));
            } catch (Throwable t) {
                println("=== FAILED ITERATION OUTPUT (WITHOUT UPTIME) ===");
                println(lastOutput);
                println("================================================");
                throw t;
            }
        }

        long averageMs = (totalDurationNanos / ITERATIONS) / 1_000_000;
        println("=== RESULT: Average bootstrap time WITHOUT Uptime (100 runs): " + averageMs + " ms ===");
    }

    @Test
    public void testBootstrapWithUptime() throws Exception {
        long totalDurationNanos = 0;
        String lastOutput;

        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            lastOutput = runIsolated(true);
            long end = System.nanoTime();
            totalDurationNanos += (end - start);

            try {
                assertTrue(lastOutput.contains("Application initialized in"));
                assertTrue(lastOutput.contains("JVM running for"));
            } catch (Throwable t) {
                println("=== FAILED ITERATION OUTPUT (WITH UPTIME) ===");
                println(lastOutput);
                println("==============================================");
                throw t;
            }
        }

        long averageMs = (totalDurationNanos / ITERATIONS) / 1_000_000;
        println("=== RESULT: Average bootstrap time WITH Uptime (100 runs): " + averageMs + " ms ===");
    }

    private String runIsolated(boolean enableUptime) throws Exception {
        var javaHome = System.getProperty("java.home");
        var javaBin = javaHome + "/bin/java";
        var classpath = System.getProperty("java.class.path");
        var className = TargetRunner.class.getName();

        var command = new ArrayList<String>();
        command.add(javaBin);
        command.add("-Dkora.log.jvm.uptime=" + enableUptime);
        command.add("-cp");
        command.add(classpath);
        command.add(className);

        var processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        var process = processBuilder.start();

        var result = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
        }
        process.waitFor();
        return result.toString();
    }

    public static final class TargetRunner {
        static void main() {
            KoraApplication.run(() -> new ApplicationGraphDraw(TargetRunner.class) {
                @Override
                public InitializedGraph init() {
                    return (InitializedGraph) java.lang.reflect.Proxy.newProxyInstance(
                        InitializedGraph.class.getClassLoader(),
                        new Class<?>[]{InitializedGraph.class},
                        (_, method, _) -> {
                            if ("release".equals(method.getName())) {
                                return null;
                            }
                            if ("draw".equals(method.getName())) {
                                return this;
                            }
                            if (method.getReturnType().equals(void.class)) {
                                return null;
                            }
                            throw new UnsupportedOperationException("Mock method not implemented: " + method.getName());
                        }
                    );
                }
            }, false);
        }
    }
}
