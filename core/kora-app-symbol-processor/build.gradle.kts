plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    id("io.koraframework.kora-hints")
    `java-test-fixtures`
}

dependencies {
    api(projects.core.symbolProcessorCommon)

    implementation(libs.jackson.core)

    testImplementation(projects.json.jsonCommon)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
