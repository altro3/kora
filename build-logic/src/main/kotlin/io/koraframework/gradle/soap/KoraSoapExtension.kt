package io.koraframework.gradle.soap

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension

open class KoraSoapExtension(
    private val project: Project,
    private val cxfConfiguration: Configuration
) {
    open fun wsdl2Java(path: String) {
        val wsdlName = path.substring(path.lastIndexOf('/') + 1).replace(".wsdl", "")
        val jakartaOutput = project.layout.buildDirectory.dir("generated/wsdl-jakarta-$wsdlName")

        val jakartaTask = project.tasks.register("wsdl-jakarta-$wsdlName", CxfGenTask::class.java) {
            inputFile.set(project.layout.projectDirectory.file(path))
            outputDir.set(jakartaOutput)
            cxfClasspath.from(cxfConfiguration)
        }

        project.pluginManager.withPlugin("java") {
            val javaExtension = project.extensions.getByType(JavaPluginExtension::class.java)
            javaExtension.sourceSets.getByName("test").java {
                srcDir(jakartaTask.flatMap { it.outputDir })
            }
        }
    }
}
