plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    api(projects.core.annotationProcessorCommon)

    implementation(projects.core.koraAppAnnotationProcessor)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(projects.experimental.s3ClientKora)
}
