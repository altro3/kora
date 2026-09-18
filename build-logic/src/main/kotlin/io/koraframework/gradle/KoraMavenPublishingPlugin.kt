package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip
import org.gradle.plugins.signing.SigningExtension
import java.net.URI

class KoraMavenPublishingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootProject = project.gradle.rootProject

        if (project == rootProject) {
            val cleanPublishDir = project.tasks.register("cleanPublishDir", Delete::class.java) {
                delete(project.layout.buildDirectory.dir("publishing-repository"))
                group = "publishing"
            }

            project.tasks.register("createPublishArchive", Zip::class.java) {
                from(project.layout.buildDirectory.dir("publishing-repository"))
                include("*/**")
                exclude("*/**/maven-metadata.*")
                group = "publishing"
                archiveFileName.set("deployment.zip")
                destinationDirectory.set(project.layout.buildDirectory)
                dependsOn(cleanPublishDir)
                dependsOn(":kora-bom:publishMavenPublicationToBuildRepository")

                rootProject.subprojects {
                    val sub = this
                    if (sub.name != "kora-bom" && !sub.path.startsWith(":internal:") && sub.childProjects.isEmpty()) {
                        sub.plugins.withId("maven-publish") {
                            this@register.dependsOn(sub.tasks.named("publishMavenPublicationToBuildRepository"))
                        }
                    }
                }
            }
            return
        }

        if (!isPublishedLibrary(project)) {
            return
        }

        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")

        project.extensions.configure(PublishingExtension::class.java) {
            publications {
                create("maven", MavenPublication::class.java) {
                    project.pluginManager.withPlugin("java-test-fixtures") {
                        val testFixturesApiElements = project.configurations.getByName("testFixturesApiElements")
                        val testFixturesRuntimeElements = project.configurations.getByName("testFixturesRuntimeElements")

                        val javaComponent = project.components.getByName("java") as AdhocComponentWithVariants
                        javaComponent.withVariantsFromConfiguration(testFixturesApiElements) { skip() }
                        javaComponent.withVariantsFromConfiguration(testFixturesRuntimeElements) { skip() }

                        suppressPomMetadataWarningsFor("testFixturesApiElements")
                        suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
                    }

                    from(project.components.getByName("java"))

                    pom {
                        name.set(project.name)
                        description.set(project.provider { "Kora ${project.name} module" })

                        licenses {
                            license {
                                name.set("The Apache Software License, Version 2.0")
                                url.set("https://github.com")
                            }
                        }
                        scm {
                            url.set("https://github.com")
                            connection.set("scm:git:git@://github.com")
                            developerConnection.set("scm:git:git@://github.com")
                        }
                        url.set("https://github.com")
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

                        withXml {
                            val element = asElement()
                            val xpf = javax.xml.xpath.XPathFactory.newInstance()
                            val xp = xpf.newXPath()
                            val xpath = xp.compile("//dependency[optional[contains(text(), 'true')]]")
                            val nl = xpath.evaluate(element, javax.xml.xpath.XPathConstants.NODESET) as org.w3c.dom.NodeList
                            for (i in nl.length - 1 downTo 0) {
                                val node = nl.item(i)
                                node.parentNode.removeChild(node)
                            }
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "build"
                    url = rootProject.layout.buildDirectory.dir("publishing-repository").map { it.asFile.toURI() }.get()
                }
                maven {
                    name = "snapshot"
                    url = URI("https://sonatype.com")
                    credentials {
                        username = project.providers.environmentVariable("SONATYPE_KORA_IO_USERNAME").orElse("").get()
                        password = project.providers.environmentVariable("SONATYPE_KORA_IO_PASSWORD").orElse("").get()
                    }
                }
            }
        }

        project.extensions.configure(SigningExtension::class.java) {
            val signingKey = project.providers.environmentVariable("KORA_IO_SIGNING_KEY").orNull
            val signingPassword = project.providers.environmentVariable("KORA_IO_SIGNING_PASSWORD").orNull
            if (signingKey == null || signingPassword == null) {
                isRequired = false
            } else {
                isRequired = true
                useInMemoryPgpKeys(signingKey, signingPassword)
                val publishing = project.extensions.getByType(PublishingExtension::class.java)
                sign(publishing.publications.getByName("maven"))
            }
        }

        project.tasks.named("publishMavenPublicationToBuildRepository").configure {
            dependsOn(rootProject.tasks.named("cleanPublishDir"))
        }
    }

    private fun isPublishedLibrary(p: Project): Boolean {
        if (!p.childProjects.isEmpty()) return false
        if (p.path.startsWith(":internal:")) return false
        if (p.name == "kora-bom") return false
        return true
    }
}
