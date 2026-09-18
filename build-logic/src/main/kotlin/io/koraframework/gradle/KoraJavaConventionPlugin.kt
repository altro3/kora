package io.koraframework.gradle

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class KoraJavaConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.childProjects.isNotEmpty() || project.name == "kora-bom") {
            return
        }

        project.plugins.apply("java-library")

        val catalogs = project.extensions.getByType<VersionCatalogsExtension>()
        val libs = catalogs.named("libs")
        val javaVersionStr = libs.findVersion("java")
            .orElseThrow { IllegalStateException("Version 'java' not found in libs.versions.toml") }
            .requiredVersion
        val javaVersionAsInt = javaVersionStr.toInt()
        val javaVersionEnum = JavaVersion.toVersion(javaVersionStr)

        project.extensions.configure(JavaPluginExtension::class.java) {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(javaVersionAsInt))
            }
            sourceCompatibility = javaVersionEnum
            targetCompatibility = javaVersionEnum
            withSourcesJar()
            withJavadocJar()
        }

        project.tasks.withType<JavaCompile>().configureEach {
            options.encoding = Charsets.UTF_8.name()
            options.isDebug = true
            options.compilerArgs.addAll(listOf("-parameters", "-XprintRounds", "--enable-preview"))
            if (name == "compileJava" && !project.name.contains("internal")) {
                options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial,-processing,-missing-explicit-ctor"))
            }
        }

        project.tasks.withType<Javadoc>().configureEach {
            val options = options as StandardJavadocDocletOptions
            options.encoding = Charsets.UTF_8.name()
            options.addBooleanOption("html5", true)
            options.addBooleanOption("-no-fonts", true)
            options.addStringOption("Xdoclint:none", "-quiet")
        }

        project.tasks.withType<Jar>().configureEach {
            manifest {
                attributes(mapOf("Automatic-Module-Name" to project.provider {
                    val moduleInfo = project.layout.projectDirectory.file("src/main/java/module-info.java")
                    if (!moduleInfo.asFile.exists()) {
                        "kora." + project.name.replace('-', '.')
                    } else {
                        null
                    }
                }))
            }
        }
        project.tasks.withType<Test>().configureEach {
            jvmArgs(
                "-XX:+TieredCompilation",
                "-XX:TieredStopAtLevel=1",
                "-XX:FlightRecorderOptions=stackdepth=1024",
                "--enable-preview",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"
            )
            useJUnitPlatform()
            testLogging {
                showStandardStreams = false
                showCauses = true
                showExceptions = true
                showStackTraces = true
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        project.pluginManager.withPlugin("java-library") {
            libs.findLibrary("jspecify").ifPresent { project.dependencies.add("api", it) }
            project.dependencies.add("testImplementation", project.dependencies.project(mapOf("path" to ":internal:test-logging")))
            libs.findLibrary("junit.jupiter").ifPresent { project.dependencies.add("testImplementation", it) }
            libs.findLibrary("mockito.core").ifPresent { project.dependencies.add("testImplementation", it) }
            libs.findLibrary("assertj").ifPresent { project.dependencies.add("testImplementation", it) }
        }

        project.tasks.register("allDeps", DependencyReportTask::class.java)
    }
}
