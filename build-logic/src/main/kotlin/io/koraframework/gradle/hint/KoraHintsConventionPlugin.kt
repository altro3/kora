package io.koraframework.gradle.hint

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register

class KoraHintsConventionPlugin : Plugin<Project> {

    companion object {
        val KORA_HINTS_ATTRIBUTE: Attribute<String> = Attribute.of("io.koraframework.hint.type", String::class.java)
    }

    override fun apply(project: Project) {
        if (project == project.rootProject) return

        val hintsElements = project.configurations.create("hintsElements") {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.attribute(KORA_HINTS_ATTRIBUTE, "kora-hints-json")
        }

        val hintsFile = project.layout.projectDirectory.file("src/main/resources/kora-module-hints.json")
        project.artifacts.add(hintsElements.name, hintsFile.asFile)

        project.pluginManager.withPlugin("java") {
            val hintsAggregation = project.configurations.create("hintsAggregation") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(project.configurations.getByName("compileClasspath"))
                attributes.attribute(KORA_HINTS_ATTRIBUTE, "kora-hints-json")
            }

            val buildHints = project.tasks.register<MergeHintsTask>("buildHints") {
                hintFiles.from(hintsAggregation.incoming.artifactView {
                    lenient(true)
                }.files)

                resultFile.set(project.layout.buildDirectory.file("generated/kora-hints/kora-hints.json"))
            }

            project.tasks.named("processResources", Copy::class.java).configure {
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
                from(buildHints.flatMap { it.resultFile })
            }

            project.tasks.named("processTestResources", Copy::class.java).configure {
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
                from(buildHints.flatMap { it.resultFile })
            }
        }
    }
}
