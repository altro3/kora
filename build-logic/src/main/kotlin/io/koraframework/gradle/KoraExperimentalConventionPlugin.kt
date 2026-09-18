package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

class KoraExperimentalConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!project.path.startsWith(":experimental:")) {
            return
        }

        project.group = "io.koraframework.experimental"

        val catalogs = project.extensions.getByType<VersionCatalogsExtension>()
        val libs = catalogs.named("libs")

        project.pluginManager.withPlugin("java") {
            libs.findLibrary("jetbrains.annotations").ifPresent { annotationsLib ->
                project.dependencies.add("implementation", annotationsLib)
            }
        }
    }
}
