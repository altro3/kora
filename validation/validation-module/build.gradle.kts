plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    compileOnly(projects.http.httpServerCommon)

    api(projects.validation.validationCommon)
}
