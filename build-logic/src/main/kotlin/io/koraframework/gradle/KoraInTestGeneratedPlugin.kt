package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class KoraInTestGeneratedPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.pluginManager.withPlugin("java") {
            val javaPlugin = project.extensions.getByType<JavaPluginExtension>()
            val sourceSets = javaPlugin.sourceSets
            val main = sourceSets.getByName("main")
            val test = sourceSets.getByName("test")

            val testGenerated = sourceSets.maybeCreate("testGenerated").apply {
                java.srcDir(project.layout.buildDirectory.dir("in-test-generated/sources"))
            }

            project.configurations.named(testGenerated.compileClasspathConfigurationName) {
                extendsFrom(project.configurations.getByName(main.compileClasspathConfigurationName))
            }
            project.configurations.named(testGenerated.runtimeClasspathConfigurationName) {
                extendsFrom(project.configurations.getByName(main.runtimeClasspathConfigurationName))
            }

            testGenerated.compileClasspath += main.output
            testGenerated.runtimeClasspath += main.output

            test.compileClasspath += testGenerated.output
            test.runtimeClasspath += testGenerated.output

            project.configurations.named(test.compileClasspathConfigurationName) {
                extendsFrom(project.configurations.getByName(testGenerated.compileClasspathConfigurationName))
            }
            project.configurations.named(test.runtimeClasspathConfigurationName) {
                extendsFrom(project.configurations.getByName(testGenerated.runtimeClasspathConfigurationName))
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val kotlinExtension = project.extensions.getByType<KotlinJvmProjectExtension>()

            kotlinExtension.sourceSets.maybeCreate("testGenerated").apply {
                val buildDir = project.layout.buildDirectory
                kotlin.srcDirs(
                    buildDir.dir("in-test-generated-ksp/ksp/sources/kotlin"),
                    buildDir.dir("in-test-generated-ksp/sources")
                )
            }
        }
    }
}
