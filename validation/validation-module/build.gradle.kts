plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    compileOnly(projects.http.httpServerCommon)

    api(projects.validation.validationCommon)
}
