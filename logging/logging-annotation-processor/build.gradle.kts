plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.aop.aopAnnotationProcessor)

    testImplementation(projects.logging.loggingCommon)
    testImplementation(projects.json.jsonAnnotationProcessor)
    testImplementation(projects.core.koraAppAnnotationProcessor)
    testImplementation(projects.logging.loggingLogback)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
}
