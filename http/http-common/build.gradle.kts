plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    compileOnly(projects.json.jsonCommon)

    api(projects.core.common)
}
