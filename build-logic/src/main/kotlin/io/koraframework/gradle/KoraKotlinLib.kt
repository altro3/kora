package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class KoraKotlinLib : Plugin<Project> {
    override fun apply(project: Project) {
        val pluginManager = project.pluginManager

        pluginManager.apply("io.koraframework.kora-java-lib")
        pluginManager.apply("io.koraframework.kora-kotlin-convention")
    }
}
