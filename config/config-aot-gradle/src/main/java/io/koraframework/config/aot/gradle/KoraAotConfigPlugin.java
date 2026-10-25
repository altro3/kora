package io.koraframework.config.aot.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import java.io.File;

public class KoraAotConfigPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {

            var aotTaskProvider = project.getTasks().register("generateKoraAotConfig", KoraAotConfigTask.class, task -> {
                File generatedSourcesDir = new File(project.getLayout().getBuildDirectory().getAsFile().get(), "generated/sources/kora-aot-config");
                task.getOutputSourceDirectory().set(generatedSourcesDir);

                JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
                SourceSet mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

                // 1. Вход: исходники текущего проекта (безопасно, не вызывают циклов)
                task.getJavaSourceDirs().from(mainSourceSet.getJava().getSourceDirectories());

                // 2. Вход: локальные ресурсы текущего проекта
                task.getClasspathResources().from(mainSourceSet.getResources().getSourceDirectories());

                // 3. Вход: внешние JAR зависимости из compileClasspath.
                // Фильтруем коллекцию, чтобы случайно не затянуть скомпилированные папки текущего проекта
                Configuration compileClasspath = project.getConfigurations().getByName("compileClasspath");
                task.getClasspathResources().from(compileClasspath.getElements().map(elements -> {
                    java.util.List<File> files = new java.util.ArrayList<>();
                    for (org.gradle.api.file.FileSystemLocation location : elements) {
                        File f = location.getAsFile();
                        // Пропускаем папки классов текущего билда, берем только JAR-ники зависимостей и ресурсы
                        if (!f.getAbsolutePath().contains(project.getLayout().getBuildDirectory().getAsFile().get().getAbsolutePath())) {
                            files.add(f);
                        }
                    }
                    return files;
                }));

                task.getConfigResource().set(project.getProviders().systemProperty("config.resource"));
                task.getConfigFile().set(project.getProviders().systemProperty("config.file"));
                task.getConfigFormat().convention("HOCON");
            });

            // Подключаем кодогенерацию к SourceSet
            JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            mainSourceSet.getJava().srcDir(aotTaskProvider.flatMap(KoraAotConfigTask::getOutputSourceDirectory));
        });
    }
}
