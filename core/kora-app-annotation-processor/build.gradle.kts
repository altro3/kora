plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    id("io.koraframework.kora-hints")
    `java-test-fixtures`
}

dependencies {
    api(projects.core.annotationProcessorCommon)

    implementation(libs.jackson.core)

    testImplementation(projects.json.jsonCommon)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
}
