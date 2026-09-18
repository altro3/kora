package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent

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

            maxParallelForks = 8
            forkEvery = 0

            maxHeapSize = "512m"
            minHeapSize = "128m"

            jvmArgs(
                "-XX:+TieredCompilation",
                "-XX:TieredStopAtLevel=1",
                "-XX:+UseParallelGC",
                "-XX:FlightRecorderOptions=stackdepth=1024",
                "--enable-preview",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"
            )

            testLogging {
                events(TestLogEvent.FAILED)
                showStandardStreams = false
                showCauses = true
                showExceptions = true
                showStackTraces = true
            }
        }
    }
}
