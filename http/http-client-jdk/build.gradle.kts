plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.http.httpClientCommon)

    testImplementation(testFixtures(projects.http.httpClientCommon))
    testImplementation(libs.jackson.coreutils)
}
