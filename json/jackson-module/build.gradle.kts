plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    compileOnly(projects.http.httpServerCommon)
    compileOnly(projects.http.httpClientCommon)

    api(libs.jackson.databind)
}
