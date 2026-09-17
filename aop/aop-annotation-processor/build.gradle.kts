plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    api(projects.core.annotationProcessorCommon)

    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
}
