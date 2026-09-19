plugins {
    `java-platform`
    id("io.koraframework.kora-publish-module")
}

val bomResolver = configurations.create("bomResolver") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bomResolver(project(":", "bomSourceElements"))
}

dependencies {
    constraints {
        bomResolver.incoming.resolutionResult.allComponents {
            val id = this.id
            if (id is ProjectComponentIdentifier && id.projectPath != project.path) {
                api(project(id.projectPath))
            }
        }
    }
}

fun modifyPom(pomNode: groovy.util.Node) {
    val javaVersionStr = libs.versions.java.get()

    val properties = pomNode.appendNode("properties")
    properties.appendNode("java.version", javaVersionStr)

    val buildNode = pomNode.appendNode("build")
    val pluginManagementNode = buildNode.appendNode("pluginManagement")
    val pluginsNode = pluginManagementNode.appendNode("plugins")

    val compilerPlugin = pluginsNode.appendNode("plugin")
    compilerPlugin.appendNode("groupId", "org.apache.maven.plugins")
    compilerPlugin.appendNode("artifactId", "maven-compiler-plugin")
    compilerPlugin.appendNode("version", libs.versions.maven.compiler.plugin.get())

    val compilerConfig = compilerPlugin.appendNode("configuration")
    compilerConfig.appendNode("release", $$"${java.version}")
    compilerConfig.appendNode("source", $$"${java.version}")
    compilerConfig.appendNode("target", $$"${java.version}")

    val compilerArgs = compilerConfig.appendNode("compilerArgs")
    compilerArgs.appendNode("arg", "-parameters")

    val processorPaths = compilerConfig.appendNode("annotationProcessorPaths")
    val pathNode = processorPaths.appendNode("path")
    pathNode.appendNode("groupId", "io.koraframework")
    pathNode.appendNode("artifactId", "annotation-processors")
    pathNode.appendNode("version", project.version.toString())

    val surefirePlugin = pluginsNode.appendNode("plugin")
    surefirePlugin.appendNode("groupId", "org.apache.maven.plugins")
    surefirePlugin.appendNode("artifactId", "maven-surefire-plugin")
    surefirePlugin.appendNode("version", libs.versions.maven.surefire.plugin.get())

    val surefireConfig = surefirePlugin.appendNode("configuration")
    surefireConfig.appendNode("argLine", "--enable-preview")
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                withXml {
                    modifyPom(asNode())
                }
            }
        }
    }
}
