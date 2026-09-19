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

class KoraHintsConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project == project.rootProject) {
            return
        }

        val root = project.rootProject

        val hintsAggregation = root.configurations.findByName("hintsAggregation") ?: root.configurations.create("hintsAggregation") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        if (root.tasks.findByName("buildHints") == null) {
            val partsDirProvider = root.layout.buildDirectory.dir("kora-hints/parts")

            val copyHints = root.tasks.register<CopyHintsTask>("copyHints") {
                from(hintsAggregation.incoming.artifactView { }.files)
                into(partsDirProvider)
            }

            root.tasks.register<MergeHintsTask>("buildHints") {
                partsDirectory.set(partsDirProvider)
                resultFile.set(root.layout.buildDirectory.file("kora-hints-generated/kora-hints.json"))
                dependsOn(copyHints)
            }
        }

        val hintsFile = project.layout.projectDirectory.file("src/main/resources/kora-module-hints.json")
        if (hintsFile.asFile.exists()) {
            val hintsElements = project.configurations.create("hintsElements") {
                isCanBeConsumed = true
                isCanBeResolved = false
            }
            project.artifacts.add(hintsElements.name, hintsFile)

            root.dependencies.add(hintsAggregation.name, project.dependencies.project(mapOf("path" to project.path, "configuration" to "hintsElements")))
        }

        project.pluginManager.withPlugin("java") {
            val javaExtension = project.extensions.getByType<JavaPluginExtension>()
            val rootBuildHints = root.tasks.named("buildHints")

            javaExtension.sourceSets.getByName("main").resources {
                srcDir(rootBuildHints.map { (it as MergeHintsTask).resultFile.get().asFile.parentFile })
            }

            project.tasks.withType<JavaCompile>().configureEach {
                dependsOn(rootBuildHints)
            }

            project.tasks.withType<Jar>().configureEach {
                if (name == "sourcesJar") {
                    dependsOn(rootBuildHints)
                }
            }

            project.tasks.withType<Copy>().configureEach {
                if (name == "processResources") {
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                }
            }
        }
    }
}
