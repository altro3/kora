plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    compileOnly(projects.json.jsonCommon)

    api(projects.core.common)
}
