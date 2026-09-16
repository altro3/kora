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

        project.extensions.configure(JavaPluginExtension::class.java) {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
            sourceCompatibility = JavaVersion.VERSION_25
            targetCompatibility = JavaVersion.VERSION_25
            withSourcesJar()
            withJavadocJar()
        }

        project.tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.isDebug = true
            options.compilerArgs.addAll(listOf("-parameters", "-XprintRounds"))
            if (name == "compileJava" && !project.name.contains("internal")) {
                options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial,-processing,-missing-explicit-ctor"))
            }
        }

        project.tasks.withType<Javadoc>().configureEach {
            val options = options as StandardJavadocDocletOptions
            options.encoding = "UTF-8"
            options.addBooleanOption("html5", true)
            options.addBooleanOption("-no-fonts", true)
            options.addStringOption("Xdoclint:none", "-quiet")
        }

        if (project.childProjects.isEmpty()) {
            val moduleInfo = project.layout.projectDirectory.file("src/main/java/module-info.java")
            if (!moduleInfo.asFile.exists()) {
                project.tasks.withType<Jar>().configureEach {
                    manifest {
                        attributes(mapOf("Automatic-Module-Name" to "kora." + project.name.replace('-', '.')))
                    }
                }
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

        val catalogs = project.extensions.getByType<VersionCatalogsExtension>()
        val libs = catalogs.named("libs")

        project.dependencies.add("api", libs.findLibrary("jspecify").get())
        project.dependencies.add("testImplementation", project.dependencies.project(mapOf("path" to ":internal:test-logging")))
        project.dependencies.add("testImplementation", libs.findLibrary("junit.jupiter").get())
        project.dependencies.add("testImplementation", libs.findLibrary("mockito.core").get())
        project.dependencies.add("testImplementation", libs.findLibrary("assertj").get())

        project.tasks.register("allDeps", DependencyReportTask::class.java)
    }
}
