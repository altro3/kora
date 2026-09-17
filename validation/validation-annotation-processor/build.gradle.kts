plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.annotationProcessorCommon)
    implementation(projects.core.koraAppAnnotationProcessor)
    implementation(projects.aop.aopAnnotationProcessor)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(projects.validation.validationCommon)
    testImplementation(projects.core.annotationProcessorCommon)
    testImplementation(projects.core.koraAppAnnotationProcessor)
    testImplementation(projects.config.configAnnotationProcessor)
    testImplementation(projects.internal.testLogging)
    testImplementation(projects.json.jsonCommon)
}
