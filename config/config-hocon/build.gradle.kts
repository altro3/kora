plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.config.configCommon)
    api(libs.typesafe.config)
}
