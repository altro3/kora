package io.koraframework.gradle.soap

import org.gradle.api.Plugin
import org.gradle.api.Project

class KoraSoapConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val cxfConfiguration = project.configurations.create("cxf")

        project.extensions.create(
            "koraSoap",
            KoraSoapExtension::class.java,
            project,
            cxfConfiguration
        )
    }
}
