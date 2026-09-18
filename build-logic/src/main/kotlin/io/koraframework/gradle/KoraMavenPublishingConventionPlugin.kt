package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import java.net.URI

class KoraMavenPublishingConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project == project.rootProject) {
            val cleanPublishDir = project.tasks.register<Delete>("cleanPublishDir") {
                delete(project.layout.buildDirectory.dir("publishing-repository"))
                group = "publishing"
            }

            project.tasks.register<Zip>("createPublishArchive") {
                from(project.layout.buildDirectory.dir("publishing-repository"))
                include("*/**")
                exclude("*/**/maven-metadata.*")
                group = "publishing"
                archiveFileName.set("deployment.zip")
                destinationDirectory.set(project.layout.buildDirectory)
                dependsOn(cleanPublishDir)
                project.rootProject.subprojects.find { it.name == "kora-bom" }?.let { bomProject ->
                    dependsOn(bomProject.tasks.named("publishMavenPublicationToBuildRepository"))
                }
            }
            return
        }

        if (!isPublishedLibrary(project)) {
            return
        }

        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")

        project.extensions.configure<PublishingExtension> {
            publications {
                create("maven", MavenPublication::class.java) {
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

                    pom {
                        name.set(project.provider { project.name })
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
                    url = project.rootProject.layout.buildDirectory.dir("publishing-repository").get().asFile.toURI()
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

        project.rootProject.tasks.withType<Zip>().configureEach {
            if (name == "createPublishArchive") {
                dependsOn(project.tasks.named("publishMavenPublicationToBuildRepository"))
            }
        }

        project.pluginManager.withPlugin("maven-publish") {
            project.tasks.named("publishMavenPublicationToBuildRepository").configure {
                dependsOn(project.rootProject.tasks.named("cleanPublishDir"))
            }
        }
    }

    private fun isPublishedLibrary(p: Project): Boolean {
        if (p.childProjects.isNotEmpty()) return false
        if (p.parent?.name == "internal") return false
        if (p.name == "kora-bom") return false
        return true
    }
}
