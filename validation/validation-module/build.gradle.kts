plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.validation.validationCommon)

    compileOnly(projects.http.httpServerCommon)
}
