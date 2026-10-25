package io.koraframework.config.aot.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@CacheableTask
public abstract class KoraAotConfigTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getJavaSourceDirs();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasspathResources();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExternalDependencies();

    @Input
    @Optional
    public abstract Property<String> getConfigResource();

    @Input
    @Optional
    public abstract Property<String> getConfigFile();

    @Input
    public abstract Property<String> getConfigFormat();

    @OutputDirectory
    public abstract DirectoryProperty getOutputSourceDirectory();

    @TaskAction
    public void generate() throws Exception {
        List<File> classpathFiles = new ArrayList<>();
        for (File file : getClasspathResources().getFiles()) {
            if (file.exists()) classpathFiles.add(file);
        }
        for (File file : getExternalDependencies().getFiles()) {
            if (file.exists()) classpathFiles.add(file);
        }

        var generator = new KoraAotGenerator(
            getJavaSourceDirs().getFiles(),
            classpathFiles,
            getConfigResource().getOrNull(),
            getConfigFile().getOrNull(),
            getConfigFormat().getOrNull(),
            getOutputSourceDirectory().get().getAsFile(),
            msg -> getLogger().lifecycle(msg)
        );

        generator.execute();
    }
}
