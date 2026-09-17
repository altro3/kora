package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

class KoraKotlinConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")

        val catalogs = project.extensions.getByType<VersionCatalogsExtension>()
        val libs = catalogs.named("libs")
        val javaVersionStr = libs.findVersion("java").get().requiredVersion
        val javaVersionAsInt = javaVersionStr.toInt()

        val kotlinExtension = project.extensions.getByType<KotlinJvmProjectExtension>()
        kotlinExtension.jvmToolchain(javaVersionAsInt)

        project.tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                javaParameters.set(true)
                freeCompilerArgs.add("-Xjvm-enable-preview")
            }
        }
    }
}
