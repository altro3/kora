package io.koraframework.gradle.hint

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register

class KoraHintsConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project == project.rootProject) return

        project.dependencies.attributesSchema {
            attribute(Usage.USAGE_ATTRIBUTE) {
                compatibilityRules.add(HintsAttributeCompatibilityRule::class.java)
                disambiguationRules.add(HintsAttributeDisambiguationRule::class.java)
            }
        }

        val hintsElements = project.configurations.create("hintsElements") {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "hintsElements"))
            }
        }

        val hintsFile = project.layout.projectDirectory.file("src/main/resources/kora-module-hints.json")
        project.artifacts.add(hintsElements.name, hintsFile.asFile)

        project.pluginManager.withPlugin("java") {
            val hintsAggregation = project.configurations.create("hintsAggregation") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(project.configurations.getByName("compileClasspath"))
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "hintsElements"))
                }
            }

            val buildHints = project.tasks.register<MergeHintsTask>("buildHints") {
                hintArtifacts.set(hintsAggregation.incoming.artifacts)
                resultFile.set(project.layout.buildDirectory.file("generated/kora-hints/kora-hints.json"))
            }

            project.tasks.named("processResources", Copy::class.java).configure {
                from(buildHints.flatMap { it.resultFile }) {
                    into("META-INF/kora")
                }
            }
        }
    }
}
