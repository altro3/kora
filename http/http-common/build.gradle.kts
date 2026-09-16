plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.core.common)
    compileOnly(projects.json.jsonCommon)
}
