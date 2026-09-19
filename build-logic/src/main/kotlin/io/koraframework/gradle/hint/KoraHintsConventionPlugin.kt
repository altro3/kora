package io.koraframework.gradle.hint

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

class KoraHintsConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootProject = project.rootProject

        if (rootProject.tasks.findByName("buildHints") == null) {

            val hintsTree = rootProject.layout.projectDirectory.asFileTree.matching {
                include("**/src/main/resources/kora-module-hints.json")
                exclude("**/build/**/*")
            }

            val partsDirProvider = rootProject.layout.buildDirectory.dir("kora-hints/parts")

            val copyHints = rootProject.tasks.register<CopyHintsTask>("copyHints") {
                from(hintsTree)
                into(partsDirProvider)
            }

            rootProject.tasks.register<MergeHintsTask>("buildHints") {
                partsDirectory.set(partsDirProvider)
                resultFile.set(rootProject.layout.buildDirectory.file("kora-hints-generated/kora-hints.json"))
                dependsOn(copyHints)
            }
        }

        val rootBuildHints = rootProject.tasks.named<MergeHintsTask>("buildHints")

        project.pluginManager.withPlugin("java") {
            val javaExtension = project.extensions.getByType<JavaPluginExtension>()

            javaExtension.sourceSets.getByName("main").resources {
                srcDir(rootBuildHints.map { it.resultFile.get().asFile.parentFile })
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
