plugins {
    id("io.koraframework.kora-kotlin-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    api(projects.core.symbolProcessorCommon)

    testImplementation(projects.kafka.kafka)
    testImplementation(projects.config.configCommon)
    testImplementation(projects.logging.loggingCommon)
    testImplementation(projects.aop.aopSymbolProcessor)
    testImplementation(projects.logging.loggingSymbolProcessor)

    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
