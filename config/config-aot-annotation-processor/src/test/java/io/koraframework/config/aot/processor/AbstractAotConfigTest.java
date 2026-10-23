package io.koraframework.config.aot.processor;

import io.koraframework.annotation.processor.common.*;
import io.koraframework.config.annotation.processor.processor.*;
import io.koraframework.config.common.aot.AotConfigResolvers;
import io.koraframework.validation.annotation.processor.*;
import org.intellij.lang.annotations.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public abstract class AbstractAotConfigTest extends AbstractAnnotationProcessorTest {

    @Override
    protected String commonImports() {
        return super.commonImports() + """
            import io.koraframework.config.common.annotation.ConfigSource;
            import io.koraframework.config.annotation.processor.*;
            """;
    }

    protected void compileAotConfig(String configFileName, String configContent, Map<String, String> profiles, @Language("java") String... sources) {
        try {
            var testClass = this.testInfo.getTestClass().get();
            var testMethod = this.testInfo.getTestMethod().get();
            var packagePath = testClass.getPackage().getName().replace('.', '/') + "/packageFor" + testClass.getSimpleName() + "/" + testMethod.getName();

            var classOutputDir = Paths.get(".", "build", "in-test-generated", "classes").resolve(packagePath);
            Files.createDirectories(classOutputDir);
            Files.writeString(classOutputDir.resolve(configFileName), configContent);
            for (var entry : profiles.entrySet()) {
                Files.writeString(classOutputDir.resolve(entry.getKey()), entry.getValue());
            }

            var targetDir = Paths.get(".", "build", "in-test-generated", "sources").resolve(packagePath);
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve(configFileName), configContent);
            for (var entry : profiles.entrySet()) {
                Files.writeString(targetDir.resolve(entry.getKey()), entry.getValue());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        super.compile(
            List.of(new ConfigParserAnnotationProcessor(), new ConfigSourceAnnotationProcessor(), new ValidAnnotationProcessor(), new AotConfigProcessor()),
            sources
        );
        compileResult.assertSuccess();
    }

    protected void compileAotConfig(String configFileName, String configContent, @Language("java") String... sources) {
        compileAotConfig(configFileName, configContent, Map.of(), sources);
    }

    @AfterEach
    void cleanupResource() {
        AotConfigResolvers.clear();

        try {
            var targetClassesDir = Paths.get("build", "in-test-generated", "classes");
            Files.deleteIfExists(targetClassesDir.resolve("application.yaml"));
            Files.deleteIfExists(targetClassesDir.resolve("application-common.yaml"));
            Files.deleteIfExists(targetClassesDir.resolve("application-local.yaml"));
            Files.deleteIfExists(targetClassesDir.resolve("application-dev.yaml"));

            var targetSourcesDir = Paths.get("build", "in-test-generated", "sources");
            Files.deleteIfExists(targetSourcesDir.resolve("application.yaml"));
            Files.deleteIfExists(targetSourcesDir.resolve("application-common.yaml"));
            Files.deleteIfExists(targetSourcesDir.resolve("application-local.yaml"));
            Files.deleteIfExists(targetSourcesDir.resolve("application-dev.yaml"));

            var resourcesDir = Paths.get("src", "main", "resources");
            Files.deleteIfExists(resourcesDir.resolve("application-common.yaml"));
            Files.deleteIfExists(resourcesDir.resolve("application-local.yaml"));
            Files.deleteIfExists(resourcesDir.resolve("application-dev.yaml"));
        } catch (IOException ignored) {}
    }
}
