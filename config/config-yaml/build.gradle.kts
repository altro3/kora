plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    api(projects.config.configCommon)
    api(libs.snakeyaml.engine)
}
