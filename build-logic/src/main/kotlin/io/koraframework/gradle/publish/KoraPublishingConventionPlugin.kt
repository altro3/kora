package io.koraframework.gradle.publish

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class KoraPublishingConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.tasks.register<SonatypePublishTask>("uploadPublishArchive") {
            dependsOn("createPublishArchive")

            version.convention(project.provider { project.version.toString() })

            archive.set(project.rootProject.layout.buildDirectory.file("deployment.zip"))

            username.convention(
                project.providers.gradleProperty("sonatypeUser")
                    .orElse(project.providers.environmentVariable("SONATYPE_KORA_IO_USERNAME"))
                    .orElse("")
            )

            password.convention(
                project.providers.gradleProperty("sonatypePassword")
                    .orElse(project.providers.environmentVariable("SONATYPE_KORA_IO_PASSWORD"))
                    .orElse("")
            )
        }
    }
}
