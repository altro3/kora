plugins {
    id("io.koraframework.kora-kotlin-module")
    id("io.koraframework.kora-in-test-generated")
    `java-test-fixtures`
}

dependencies {
    api(projects.core.symbolProcessorCommon)

    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(projects.scheduling.schedulingJdk)
    testImplementation(projects.scheduling.schedulingQuartz)
    testImplementation(projects.scheduling.schedulingDbScheduler)
    testImplementation(projects.config.configAnnotationProcessor)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}
