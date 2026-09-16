plugins {
    id("io.koraframework.kora-java-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.annotationProcessorCommon)
    implementation(projects.core.koraAppAnnotationProcessor)
    implementation(libs.javapoet)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(projects.aop.aopAnnotationProcessor)
    testImplementation(projects.internal.testLogging)
    testImplementation(projects.experimental.camundaZeebeWorker)
}
