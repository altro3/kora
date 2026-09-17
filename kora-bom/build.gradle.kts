plugins {
    `java-platform`
    `maven-publish`
    signing
}

dependencies {
    constraints {
        if (rootProject.ext.has("koraBomProjectNames")) {
            @Suppress("UNCHECKED_CAST")
            val koraProjectNames = rootProject.ext["koraBomProjectNames"] as List<String>
            val currentGroup = project.group.toString()
            val currentVersion = project.version.toString()

            for (projectName in koraProjectNames) {
                api("$currentGroup:$projectName:$currentVersion")
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kora-bom"
            from(components["javaPlatform"])

            val javaVersionValue = libs.versions.java.get()
            val mavenCompilerPluginVersion = libs.versions.maven.compiler.plugin.get()
            val mavenSurefirePluginVersion = libs.versions.maven.surefire.plugin.get()
            val projectVersionValue = project.version.toString()

            pom {
                name.set("Kora BOM")
                description.set("Kora Bill-Of-Materials (BOM)")
                url.set("https://github.com/kora-projects/kora")

                withXml {
                    val root = asElement()
                    val doc = root.ownerDocument

                    val properties = doc.createElement("properties")
                    val javaVersion = doc.createElement("java.version")
                    javaVersion.textContent = javaVersionValue
                    properties.appendChild(javaVersion)
                    root.appendChild(properties)

                    val build = doc.createElement("build")
                    val plugins = doc.createElement("plugins")

                    val compilerPlugin = doc.createElement("plugin")
                    compilerPlugin.appendChild(doc.createElement("groupId").apply { textContent = "org.apache.maven.plugins" })
                    compilerPlugin.appendChild(doc.createElement("artifactId").apply { textContent = "maven-compiler-plugin" })
                    compilerPlugin.appendChild(doc.createElement("version").apply { textContent = mavenCompilerPluginVersion })

                    val compilerConfig = doc.createElement("configuration")
                    compilerConfig.appendChild(doc.createElement("release").apply { textContent = $$"${java.version}" })
                    compilerConfig.appendChild(doc.createElement("source").apply { textContent = $$"${java.version}" })
                    compilerConfig.appendChild(doc.createElement("target").apply { textContent = $$"${java.version}" })

                    val compilerArgs = doc.createElement("compilerArgs")
                    compilerArgs.appendChild(doc.createElement("arg").apply { textContent = "-parameters" })
                    compilerConfig.appendChild(compilerArgs)

                    val processorPaths = doc.createElement("annotationProcessorPaths")
                    val path = doc.createElement("path")
                    path.appendChild(doc.createElement("groupId").apply { textContent = "io.koraframework" })
                    path.appendChild(doc.createElement("artifactId").apply { textContent = "annotation-processors" })
                    path.appendChild(doc.createElement("version").apply { textContent = projectVersionValue })
                    processorPaths.appendChild(path)
                    compilerConfig.appendChild(processorPaths)

                    compilerPlugin.appendChild(compilerConfig)
                    plugins.appendChild(compilerPlugin)

                    val surefirePlugin = doc.createElement("plugin")
                    surefirePlugin.appendChild(doc.createElement("groupId").apply { textContent = "org.apache.maven.plugins" })
                    surefirePlugin.appendChild(doc.createElement("artifactId").apply { textContent = "maven-surefire-plugin" })
                    surefirePlugin.appendChild(doc.createElement("version").apply { textContent = mavenSurefirePluginVersion })

                    val surefireConfig = doc.createElement("configuration")
                    surefireConfig.appendChild(doc.createElement("argLine").apply { textContent = "--enable-preview" })

                    surefirePlugin.appendChild(surefireConfig)
                    plugins.appendChild(surefirePlugin)

                    build.appendChild(plugins)
                    root.appendChild(build)
                }

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
