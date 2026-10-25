package io.koraframework.config.aot.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;

public class KoraAotConfigPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply("java");

        var javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        var mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        var aotTask = project.getTasks().register("koraAotConfig", KoraAotConfigTask.class, task -> {
            task.getJavaSourceDirs().from(project.file("src/main/java"));
            task.getClasspathResources().from(mainSourceSet.getResources().getSourceDirectories());

            var compileClasspath = project.getConfigurations().getByName("compileClasspath");
            task.getExternalDependencies().from(compileClasspath.getIncoming().getArtifacts().getArtifactFiles());

            var buildDir = project.getLayout().getBuildDirectory();
            task.getOutputSourceDirectory().convention(buildDir.dir("generated/sources/kora-aot/java/main"));
            task.getConfigFormat().convention("HOCON");
        });

        mainSourceSet.getJava().srcDir(aotTask.flatMap(KoraAotConfigTask::getOutputSourceDirectory));

        project.getTasks().configureEach(task -> {
            var name = task.getName();
            if (task instanceof JavaCompile ||
                name.startsWith("ksp") ||
                name.startsWith("compileKotlin")) {
                task.dependsOn(aotTask);
            }
        });
    }
}
