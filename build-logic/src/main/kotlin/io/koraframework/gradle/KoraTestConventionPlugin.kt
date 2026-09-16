package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test

class KoraTestConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
        val libs = catalogs.named("libs")

        if (project.parent?.name != "internal") {
            return
        }

        project.dependencies.add("api", libs.findLibrary("junit.platform.launcher").get())
        project.dependencies.add("api", libs.findLibrary("junit.jupiter").get())

        project.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform {
                systemProperties(
                    mapOf(
                        "junit.jupiter.execution.parallel.enabled" to "true",
                        "junit.jupiter.execution.parallel.mode.default" to "concurrent",
                        "junit.jupiter.execution.parallel.mode.classes.default" to "concurrent",
                    )
                )
            }

            maxHeapSize = "512m"
            minHeapSize = "128m"
            forkEvery = 0
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            jvmArgs("-XX:+UseParallelGC")

            testLogging {
                events("passed", "skipped", "failed")
                showExceptions = true
                showCauses = true
                showStackTraces = false
            }
        }
    }
}
