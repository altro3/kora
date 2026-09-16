package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

class KoraKotlinConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")

        val kotlinExtension = project.extensions.getByType<KotlinJvmProjectExtension>()
        kotlinExtension.jvmToolchain(25)

        project.tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                javaParameters.set(true)
            }
        }
    }
}
