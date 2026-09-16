plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(libs.jackson.databind)

    compileOnly(projects.http.httpServerCommon)
    compileOnly(projects.http.httpClientCommon)
}
