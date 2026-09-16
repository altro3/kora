plugins {
    id("io.koraframework.kora-kotlin-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    api(projects.core.symbolProcessorCommon)

    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
    testImplementation(libs.mockito.kotlin)
}
