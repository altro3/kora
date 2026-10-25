package io.koraframework.config.aot.gradle;

import org.gradle.api.*;
import org.gradle.api.artifacts.*;
import org.gradle.api.file.*;
import org.gradle.api.plugins.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import java.io.*;

public class KoraAotConfigPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, _ -> {

            JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            SourceSet aotSourceSet = javaExtension.getSourceSets().create("aot");

            Configuration aotCompileClasspath = project.getConfigurations().getByName(aotSourceSet.getCompileClasspathConfigurationName());
            Configuration runtimeClasspath = project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
            aotCompileClasspath.extendsFrom(runtimeClasspath);

            TaskProvider<Jar> jarTask = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
            project.getDependencies().add(aotSourceSet.getImplementationConfigurationName(), project.getLayout().files(jarTask));

            var aotTaskProvider = project.getTasks().register("generateKoraAotConfig", KoraAotConfigTask.class, task -> {
                File generatedSourcesDir = new File(project.getLayout().getBuildDirectory().getAsFile().get(), "generated/sources/kora-aot-config");
                task.getOutputSourceDirectory().set(generatedSourcesDir);

                task.getJavaSourceDirs().from(mainSourceSet.getJava().getSourceDirectories());
                task.getClasspathResources().from(mainSourceSet.getResources().getSourceDirectories());

                task.getExternalDependencies().from(aotCompileClasspath);

                task.getConfigResource().set(project.getProviders().systemProperty("config.resource"));
                task.getConfigFile().set(project.getProviders().systemProperty("config.file"));
                task.getConfigFormat().convention("HOCON");
            });

            aotSourceSet.getJava().srcDir(aotTaskProvider.flatMap(KoraAotConfigTask::getOutputSourceDirectory));

            TaskProvider<JavaCompile> compileAotJava = project.getTasks().named(aotSourceSet.getCompileJavaTaskName(), JavaCompile.class, compile -> {
                compile.setSource(aotTaskProvider.flatMap(KoraAotConfigTask::getOutputSourceDirectory));
                compile.getDestinationDirectory().set(new File(project.getLayout().getBuildDirectory().getAsFile().get(), "classes/java/aot"));
                compile.getOptions().setAnnotationProcessorPath(project.getObjects().fileCollection());
                compile.dependsOn(aotTaskProvider);
            });

            project.getPluginManager().withPlugin("com.github.johnrengelman.shadow", _ -> {
                project.getTasks().register("optimizedShadowJar", Jar.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Assemble a shadow jar containing Kora AOT compiled configurations.");

                    Jar originalShadow = (Jar) project.getTasks().getByName("shadowJar");

                    task.getArchiveBaseName().set("service-kora-aot");
                    task.getArchiveClassifier().set("");
                    task.setDuplicatesStrategy(originalShadow.getDuplicatesStrategy());

                    task.from(originalShadow.getSource());
                    task.from(compileAotJava.flatMap(JavaCompile::getDestinationDirectory));

                    project.getTasks().matching(t -> t.getName().equals("shadowJar")).configureEach(_ -> {
                        if (originalShadow.getActions() != null) {
                            originalShadow.getActions().forEach(action -> {
                                try {
                                    task.doFirst(action);
                                } catch (Exception ignored) {}
                            });
                        }
                    });

                    task.dependsOn(compileAotJava);
                });
            });

            project.getPluginManager().withPlugin("com.google.cloud.tools.jib", _ -> {
                project.getTasks().register("optimizedJib", task -> {
                    task.setGroup("publishing");
                    task.setDescription("Builds a container image using Kora AOT compiled configurations.");
                    task.dependsOn(compileAotJava);
                    task.dependsOn(project.getTasks().named("jib"));
                });

                project.getTasks().register("optimizedJibDockerBuild", task -> {
                    task.setGroup("publishing");
                    task.setDescription("Builds a container image to a Docker daemon using Kora AOT compiled configurations.");
                    task.dependsOn(compileAotJava);
                    task.dependsOn(project.getTasks().named("jibDockerBuild"));
                });
            });
        });
    }
}
