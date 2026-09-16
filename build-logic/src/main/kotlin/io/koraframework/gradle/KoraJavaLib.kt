package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class KoraJavaLib : Plugin<Project> {
    override fun apply(project: Project) {
        val pluginManager = project.pluginManager

        pluginManager.apply("java")

        pluginManager.apply("io.koraframework.kora-java-convention")
        pluginManager.apply("io.koraframework.kora-dependency-alignment")
        pluginManager.apply("io.koraframework.kora-experimental-convention")
        pluginManager.apply("io.koraframework.kora-maven-publishing")
        pluginManager.apply("io.koraframework.kora-test-convention")
    }
}
