plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.koraAppSymbolProcessor)

    testImplementation(libs.konvert.api)
    testImplementation(projects.core.koraAppSymbolProcessor)
    testImplementation(projects.core.symbolProcessorCommon)
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
