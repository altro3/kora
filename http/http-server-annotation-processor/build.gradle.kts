plugins {
    id("io.koraframework.kora-kotlin-lib")
    id("io.koraframework.kora-in-test-generated")
    `java-test-fixtures`
}

dependencies {
    api(projects.core.annotationProcessorCommon)
    api(projects.core.koraAppAnnotationProcessor)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(projects.http.httpServerCommon)
    testImplementation(projects.json.jsonCommon)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.byte.buddy.core)
    testImplementation(libs.byte.buddy.agent)
}
