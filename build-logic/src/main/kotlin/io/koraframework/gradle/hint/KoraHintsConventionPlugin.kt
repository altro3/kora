package io.koraframework.gradle.hint

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
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
        if (project == project.rootProject) return

        val hintsFile = project.layout.projectDirectory.file("src/main/resources/kora-module-hints.json")
        if (hintsFile.asFile.exists()) {
            project.configurations.create("hintsElements") {
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "hintsElements"))
                }
            }
            project.artifacts.add("hintsElements", hintsFile)
        }

        project.pluginManager.withPlugin("java") {
            val javaExtension = project.extensions.getByType<JavaPluginExtension>()

            val hintsAggregation = project.configurations.create("hintsAggregation") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(project.configurations.getByName("compileClasspath"))
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "hintsElements"))
                }
            }

            val partsDirProvider = project.layout.buildDirectory.dir("kora-hints/parts")

            val copyHints = project.tasks.register<CopyHintsTask>("copyHints") {
                from(hintsAggregation.incoming.files)
                into(partsDirProvider)
            }

            val buildHints = project.tasks.register<MergeHintsTask>("buildHints") {
                partsDirectory.set(partsDirProvider)
                resultFile.set(project.layout.buildDirectory.file("kora-hints-generated/kora-hints.json"))
                dependsOn(copyHints)
            }

            javaExtension.sourceSets.getByName("main").resources {
                srcDir(buildHints.map { it.resultFile.get().asFile.parentFile })
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
                }
            }
        }
    }
}
