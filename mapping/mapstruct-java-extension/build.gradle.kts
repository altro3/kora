plugins {
    id("io.koraframework.kora-java-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.koraAppAnnotationProcessor)

    testImplementation(libs.mapstruct)
    testImplementation(libs.mapstruct.processor)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(libs.jakarta.inject.api)
}
