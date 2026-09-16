package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class KoraTestConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
        val libs = catalogs.named("libs")

        if (project.parent?.name != "internal") {
            return
        }

        project.dependencies.add("api", libs.findLibrary("junit.platform.launcher").get())
        project.dependencies.add("api", libs.findLibrary("junit.jupiter").get())
    }
}
