plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.annotationProcessorCommon)
    implementation(projects.core.koraAppAnnotationProcessor)

    testImplementation(projects.kafka.kafka)
    testImplementation(projects.config.configCommon)
    testImplementation(projects.logging.loggingCommon)
    testImplementation(projects.aop.aopAnnotationProcessor)
    testImplementation(projects.logging.loggingAnnotationProcessor)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
}
