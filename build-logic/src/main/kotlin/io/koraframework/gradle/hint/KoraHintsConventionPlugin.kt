package io.koraframework.gradle.hint

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.named

class KoraHintsConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootProject = project.rootProject

        if (rootProject.tasks.findByName("buildHints") == null) {
            val hintsTree = rootProject.layout.projectDirectory.asFileTree.matching {
                include("**/src/main/resources/kora-module-hints.json")
                exclude("**/build/**/*")
            }

            val copyHints = rootProject.tasks.register("copyHints", CopyHintsTask::class.java) {
                hintFiles.from(hintsTree)
                outputDirectory.set(rootProject.layout.buildDirectory.dir("kora-hints/parts"))
            }

            rootProject.tasks.register("buildHints", MergeHintsTask::class.java) {
                partsDirectory.set(copyHints.flatMap { it.outputDirectory })
                resultFile.set(rootProject.layout.buildDirectory.file("kora-hints-generated/kora-hints.json"))
            }
        }

        val rootBuildHints = rootProject.tasks.named<MergeHintsTask>("buildHints")

        project.pluginManager.withPlugin("java") {
            val javaExtension = project.extensions.getByType(JavaPluginExtension::class.java)
            javaExtension.sourceSets.getByName("main").resources {
                srcDir(rootBuildHints.map { it.resultFile.get().asFile.parentFile })
            }

            project.tasks.withType(Copy::class.java).configureEach {
                if (name == "processResources") {
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                }
            }
        }
    }
}
