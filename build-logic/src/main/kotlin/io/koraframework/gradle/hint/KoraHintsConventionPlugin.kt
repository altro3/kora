package io.koraframework.gradle.hint

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File

class KoraHintsConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootDirFile = project.rootProject.rootDir
        val localTargetDirProvider = project.layout.buildDirectory.dir("kora-hints-generated")
        val localHintsFile = project.layout.projectDirectory.file("src/main/resources/kora-module-hints.json")

        val buildHints = project.tasks.register<MergeHintsTask>("buildHints") {
            hintFiles.from(project.provider {
                val paths = mutableListOf<File>()
                rootDirFile.walkTopDown()
                    .filter { it.isFile && it.name == "kora-module-hints.json" && !it.absolutePath.contains("${File.separator}build${File.separator}") }
                    .forEach { paths.add(it) }
                paths
            })

            if (localHintsFile.asFile.exists()) {
                hintFiles.from(localHintsFile)
            }

            resultFile.set(localTargetDirProvider.map { it.file("kora-hints.json") })
        }

        project.pluginManager.withPlugin("java") {
            val javaExtension = project.extensions.getByType<JavaPluginExtension>()

            javaExtension.sourceSets.getByName("main").resources {
                srcDir(localTargetDirProvider)
            }

            project.tasks.withType<JavaCompile>().configureEach {
                dependsOn(buildHints)
            }

            project.tasks.withType<Jar>().configureEach {
                if (name == "sourcesJar") {
                    dependsOn(buildHints)
                }
            }

            project.tasks.withType<Copy>().configureEach {
                if (name == "processResources") {
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                    dependsOn(buildHints)
                }
            }
        }
    }
}
