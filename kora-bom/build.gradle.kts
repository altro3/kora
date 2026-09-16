plugins {
    `java-platform`
    `maven-publish`
    signing
}

dependencies {
    constraints {
        val added = hashSetOf<String>()

        rootProject.subprojects {
            val p = this
            if (p.parent?.name == "internal") return@subprojects
            if (p.subprojects.isNotEmpty()) return@subprojects

            if (added.add(p.name)) {
                api(p)
            }
        }
    }
}

fun modifyPom(pomNode: groovy.util.Node) {
    val properties = pomNode.appendNode("properties")
    properties.appendNode("java.version", "25")

    val buildNode = pomNode.appendNode("build")
    val pluginsNode = buildNode.appendNode("plugins")

    val compilerPlugin = pluginsNode.appendNode("plugin")
    compilerPlugin.appendNode("groupId", "org.apache.maven.plugins")
    compilerPlugin.appendNode("artifactId", "maven-compiler-plugin")
    compilerPlugin.appendNode("version", libs.versions.maven.compiler.plugin.get())

    val compilerConfig = compilerPlugin.appendNode("configuration")
    compilerConfig.appendNode("release", "\${java.version}")
    compilerConfig.appendNode("source", "\${java.version}")
    compilerConfig.appendNode("target", "\${java.version}")

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
        create<MavenPublication>("maven") {
            artifactId = "kora-bom"
            from(components["javaPlatform"])
            // remove scope information from published BOM
            pom {
                withXml {
                    modifyPom(asNode())
                }
                name.set("Kora BOM")
                description.set("Kora Bill-Of-Materials (BOM)")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://github.com/kora-projects/kora/blob/master/LICENSE")
                    }
                }
                scm {
                    url.set("https://github.com/kora-projects/kora")
                    connection.set("scm:git:git@github.com/kora-projects/kora.git")
                    developerConnection.set("scm:git:git@github.com/kora-projects/kora.git")
                }
                url.set("https://github.com/kora-projects/kora")
                developers {
                    developer {
                        id.set("a.otts")
                        name.set("Aleksei Otts")
                        email.set("eld0727@mail.ru")
                    }
                    developer {
                        id.set("a.duyun")
                        name.set("Anton Duyun")
                        email.set("anton.duyun@gmail.com")
                    }
                    developer {
                        id.set("a.kurako")
                        name.set("Anton Kurako")
                        email.set("goodforgod.dev@gmail.com")
                    }
                    developer {
                        id.set("a.yakovlev")
                        name.set("Artem Yakovlev")
                        email.set("jakart89@gmail.com")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "build"
            url = rootProject.layout.buildDirectory.dir("publishing-repository").get().asFile.toURI()
        }
        maven {
            name = "snapshot"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials {
                username = providers.environmentVariable("SONATYPE_KORA_IO_USERNAME").orElse("").get()
                password = providers.environmentVariable("SONATYPE_KORA_IO_PASSWORD").orElse("").get()
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("KORA_IO_SIGNING_KEY")
    val signingPassword = providers.environmentVariable("KORA_IO_SIGNING_PASSWORD")

    if (!signingKey.isPresent || !signingPassword.isPresent) {
        isRequired = false
    } else {
        isRequired = true
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publishing.publications["maven"])
    }
}
