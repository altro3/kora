package io.koraframework.config.aot.processor;

import io.koraframework.annotation.processor.common.*;
import io.koraframework.config.annotation.processor.processor.*;
import io.koraframework.validation.annotation.processor.*;
import org.intellij.lang.annotations.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public abstract class AbstractAotConfigTest extends AbstractAnnotationProcessorTest {
    private Path createdResourcePath;

    @Override
    protected String commonImports() {
        return super.commonImports() + """
            import io.koraframework.config.common.annotation.ConfigSource;
            import io.koraframework.config.annotation.processor.*;
            """;
    }

    protected void compileAotConfig(String configFileName, String configContent, @Language("java") String... sources) {
        try {
            var targetDir = Paths.get("build", "in-test-generated", "sources");
            Files.createDirectories(targetDir);
            createdResourcePath = targetDir.resolve(configFileName);
            Files.writeString(createdResourcePath, configContent);

            var classOutputDir = Paths.get("build", "in-test-generated", "classes");
            Files.createDirectories(classOutputDir);
            Files.writeString(classOutputDir.resolve(configFileName), configContent);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        super.compile(
            List.of(
                new ConfigParserAnnotationProcessor(),
                new ConfigSourceAnnotationProcessor(),
                new ValidAnnotationProcessor(),
                new AotConfigProcessor()
            ),
            sources
        );
        compileResult.assertSuccess();
    }

    @AfterEach
    void cleanupResource() {
        if (createdResourcePath != null) {
            try {
                Files.deleteIfExists(createdResourcePath);
            } catch (IOException ignored) {}
        }
    }
}
