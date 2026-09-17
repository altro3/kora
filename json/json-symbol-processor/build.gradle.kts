plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    alias(libs.plugins.jmh)
}

dependencies {
    api(projects.core.symbolProcessorCommon)
    api(projects.core.koraAppSymbolProcessor)

    testImplementation(projects.json.jsonCommon)
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
