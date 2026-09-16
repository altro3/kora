plugins {
    id("io.koraframework.kora-java-lib")
}

description = "Kora HTTP Client implementation based on Apache HttpClient 5"

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.http.httpClientCommon)
    api(libs.apache.httpclient)

    testImplementation(testFixtures(projects.http.httpClientCommon))
    testImplementation(libs.jackson.coreutils)
}
