plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.annotationProcessorCommon)
    implementation(projects.core.koraAppAnnotationProcessor)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(projects.http.httpClientCommon)
    testImplementation(projects.config.configAnnotationProcessor)
    testImplementation(projects.core.common)
    testImplementation(projects.logging.loggingCommon)
}
