package io.koraframework.gradle.test

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoCoverageReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

@Suppress("UnstableApiUsage")
class KoraCoverageConventionPlugin : Plugin<Project> {
override fun apply(project: Project) {
if (project != project.rootProject) {
return
}

project.pluginManager.apply("jacoco-report-aggregation")

project.extensions.configure<ReportingExtension> {
reports {
create<JacocoCoverageReport>("testCodeCoverageReport") {
testSuiteName.set("test")
}
}
}

project.tasks.named<JacocoReport>("testCodeCoverageReport").configure {
reports {
xml.required.set(true)
html.required.set(true)
}
}

project.configurations.named("jacocoAggregation").configure {
dependencies.addAllLater(project.provider {
project.subprojects
.filter { it.childProjects.isEmpty() && it.name != "kora-bom" }
.map { project.dependencies.project(mapOf("path" to it.path)) }
})
}

project.subprojects {
if (childProjects.isNotEmpty() || name == "kora-bom") {
return@subprojects
}

pluginManager.apply("jacoco")

tasks.withType<JacocoReport>().configureEach {
reports {
xml.required.set(true)
html.required.set(true)
}
}

tasks.withType<Test>().configureEach {
extensions.configure<JacocoTaskExtension> {
excludes = listOf("**.packageFor**")
}
}
}
}
}
