plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.symbolProcessorCommon)
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(projects.experimental.s3ClientKora)
    testImplementation(projects.internal.testLogging)
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
    testImplementation(libs.kotlin.stdlib.lib)
}
