plugins {
    id("io.koraframework.kora-kotlin-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.koraAppSymbolProcessor)

    testImplementation(libs.mapstruct)
    testImplementation(libs.mapstruct.processor)
    testImplementation(libs.jakarta.inject.api)
    testImplementation(projects.core.koraAppSymbolProcessor)
    testImplementation(projects.core.symbolProcessorCommon)
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
