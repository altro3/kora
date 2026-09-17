plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.symbolProcessorCommon)
    implementation(projects.aop.aopSymbolProcessor)

    testImplementation(projects.json.jsonCommon)
    testImplementation(projects.json.jsonSymbolProcessor)
    testImplementation(projects.logging.loggingCommon)
    testImplementation(projects.logging.loggingLogback)
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
