plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    `java-test-fixtures`
}

dependencies {
    implementation(projects.aop.aopSymbolProcessor)
    implementation(libs.ksp.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
    testImplementation(projects.resilient.resilientKora)
    testImplementation(projects.resilient.resilientKoraDistributed)
    testImplementation(projects.internal.testLogging)
    testImplementation(projects.core.symbolProcessorCommon)
    testImplementation(projects.aop.aopSymbolProcessor)
    testImplementation(projects.config.configHocon)
    testImplementation(projects.config.configSymbolProcessor)
    testImplementation(projects.core.koraAppSymbolProcessor)
    testImplementation(libs.kotlin.stdlib.lib)
    testImplementation(libs.awaitility)
}
