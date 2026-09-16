package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class KoraDependencyAlignment : Plugin<Project> {
    override fun apply(project: Project) {
        val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
        val libs = catalogs.named("libs")

        project.configurations.configureEach {
            resolutionStrategy {
                eachDependency {
                    val group = requested.group
                    val name = requested.name

                    val versionProvider = when {
                        group.startsWith("io.netty") -> libs.findVersion("netty")
                        group.startsWith("io.grpc") -> libs.findVersion("grpc-java")
                        group.startsWith("com.google.protobuf") -> libs.findVersion("protobuf-java")
                        group.startsWith("com.fasterxml.jackson") -> {
                            if (name == "jackson-annotations") {
                                libs.findVersion("jackson2-annotations")
                            } else {
                                libs.findVersion("jackson2-coreline")
                            }
                        }

                        group.startsWith("io.prometheus") -> libs.findVersion("prometheus-metrics")
                        group.startsWith("io.swagger.core.v3") -> libs.findVersion("swagger-coreline")
                        group.startsWith("io.swagger.parser.v3") -> libs.findVersion("swagger-parser")
                        group == "org.jetbrains" && name == "annotations" -> libs.findVersion("jetbrains-annotations")
                        group == "org.jetbrains.kotlin" && name.startsWith("kotlin-stdlib") -> libs.findVersion("kotlin-stdlib")
                        group == "com.squareup" && name.startsWith("kotlinpoet") -> libs.findVersion("kotlinpoet")
                        group == "org.junit.jupiter" || name.startsWith("junit-platform") -> libs.findVersion("junit")
                        group == "org.slf4j" -> libs.findVersion("slf4j")
                        group == "ch.qos.logback" -> libs.findVersion("logback")
                        group == "com.google.guava" && name == "guava" -> libs.findVersion("guava")
                        group == "net.bytebuddy" && name.startsWith("byte-buddy") -> libs.findVersion("byte-buddy")
                        group == "commons-cli" -> libs.findVersion("commons-cli")
                        group == "commons-codec" -> libs.findVersion("commons-codec")
                        group == "commons-collections" -> libs.findVersion("commons-collections")
                        group == "commons-fileupload" -> libs.findVersion("commons-fileupload")
                        group == "commons-io" -> libs.findVersion("commons-io")
                        group == "commons-logging" -> libs.findVersion("commons-logging")
                        group == "org.apache.commons" && name == "commons-lang3" -> libs.findVersion("commons-lang3")
                        group == "org.apache.commons" && name == "commons-text" -> libs.findVersion("commons-text")
                        else -> null
                    }

                    if (versionProvider != null && versionProvider.isPresent) {
                        useVersion(versionProvider.get().toString())
                    }
                }

                val jackson2AnnotationsVersion = libs.findVersion("jackson2-annotations")
                if (jackson2AnnotationsVersion.isPresent) {
                    force("com.fasterxml.jackson.core:jackson-annotations:${jackson2AnnotationsVersion.get()}")
                }
            }
        }
    }
}
