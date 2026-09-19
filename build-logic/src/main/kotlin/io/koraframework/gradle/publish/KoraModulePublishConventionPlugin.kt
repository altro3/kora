package io.koraframework.gradle.publish

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.NodeList
import java.net.URI
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class KoraModulePublishConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project == project.rootProject || !isPublishedLibrary(project)) return

        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")

        project.extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "build"
                    url = project.rootProject.layout.buildDirectory.dir("publishing-repository").get().asFile.toURI()
                }
                maven {
                    name = "snapshot"
                    url = URI("https://central.sonatype.com/repository/maven-snapshots/")
                    credentials {
                        username = project.providers.environmentVariable("SONATYPE_KORA_IO_USERNAME").orElse("").get()
                        password = project.providers.environmentVariable("SONATYPE_KORA_IO_PASSWORD").orElse("").get()
                    }
                }
            }

            publications {
                create<MavenPublication>("maven") {
                    project.pluginManager.withPlugin("java") {
                        if (project.pluginManager.hasPlugin("java-test-fixtures")) {
                            val testFixturesApiElements = project.configurations.getByName("testFixturesApiElements")
                            val testFixturesRuntimeElements = project.configurations.getByName("testFixturesRuntimeElements")

                            val javaComponent = project.components.getByName("java") as AdhocComponentWithVariants
                            javaComponent.withVariantsFromConfiguration(testFixturesApiElements) { skip() }
                            javaComponent.withVariantsFromConfiguration(testFixturesRuntimeElements) { skip() }

                            suppressPomMetadataWarningsFor("testFixturesApiElements")
                            suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
                        }
                        from(project.components.getByName("java"))
                    }

                    project.pluginManager.withPlugin("java-platform") {
                        from(project.components.getByName("javaPlatform"))
                    }

                    pom {
                        name.set(project.provider {
                            if (project.name == "kora-bom") "Kora BOM" else project.name
                        })
                        description.set(project.provider {
                            if (project.name == "kora-bom") "Kora Bill-Of-Materials (BOM)" else "Kora ${project.name} module"
                        })
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
                            val xpf = XPathFactory.newInstance()
                            val xp = xpf.newXPath()
                            val xpath = xp.compile("//dependency[optional[contains(text(), 'true')]]")
                            val nl = xpath.evaluate(element, XPathConstants.NODESET) as NodeList
                            for (i in nl.length - 1 downTo 0) {
                                val node = nl.item(i)
                                node.parentNode.removeChild(node)
                            }
                        }
                    }
                }
            }
        }

        project.extensions.configure<SigningExtension> {
            val signingKey = project.providers.environmentVariable("KORA_IO_SIGNING_KEY").orNull
            val signingPassword = project.providers.environmentVariable("KORA_IO_SIGNING_PASSWORD").orNull
            if (signingKey == null || signingPassword == null) {
                isRequired = false
            } else {
                isRequired = true
                useInMemoryPgpKeys(signingKey, signingPassword)
                val publishing = project.extensions.getByType<PublishingExtension>()
                sign(publishing.publications.getByName("maven"))
            }
        }

        project.pluginManager.withPlugin("maven-publish") {
            val publishTask = project.tasks.named("publishMavenPublicationToBuildRepository")

            publishTask.configure {
                dependsOn(project.rootProject.tasks.named("cleanPublishDir"))
            }

            val publishingElements = project.configurations.create("publishingElements") {
                isCanBeConsumed = true
                isCanBeResolved = false
            }

            project.artifacts.add(publishingElements.name, project.layout.buildDirectory.file("publishing-repository")) {
                builtBy(publishTask)
            }
        }
    }

    private fun isPublishedLibrary(p: Project): Boolean {
        if (p.childProjects.isNotEmpty()) return false
        if (p.path.contains(":internal:") || p.parent?.name == "internal") return false
        return true
    }
}
