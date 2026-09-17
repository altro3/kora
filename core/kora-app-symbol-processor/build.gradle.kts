import io.koraframework.gradle.hint.CopyHintsTask
import io.koraframework.gradle.hint.MergeHintsTask

plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    `java-test-fixtures`
}

dependencies {
    api(projects.core.symbolProcessorCommon)

    implementation(libs.jackson.core)

    testImplementation(projects.json.jsonCommon)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("kora-hints-generated"))
        }
    }
}

val copyHints = tasks.register<CopyHintsTask>("copyHints") {
    repositoryRoot.set(layout.projectDirectory.dir("../.."))
    outputDirectory.set(layout.buildDirectory.dir("kora-hints/parts"))
}
val buildHints = tasks.register<MergeHintsTask>("buildHints") {
    dependsOn(copyHints)
    partsDirectory.set(layout.buildDirectory.dir("kora-hints/parts"))
    resultFile.set(layout.buildDirectory.file("kora-hints/kora-hints.json"))
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(buildHints)
    from(buildHints.flatMap { it.resultFile })
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(buildHints)
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(buildHints)
}
tasks.matching { it.name.startsWith("kspKotlin") || it.name.startsWith("kspJava") }.configureEach {
    dependsOn(buildHints)
}
