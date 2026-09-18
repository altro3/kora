plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    api(projects.core.annotationProcessorCommon)

    implementation(projects.core.koraAppAnnotationProcessor)

    testImplementation(projects.config.configCommon)
    testImplementation(projects.validation.validationAnnotationProcessor)
    testImplementation(projects.validation.validationCommon)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
}
