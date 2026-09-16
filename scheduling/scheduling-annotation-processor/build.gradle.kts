plugins {
    id("io.koraframework.kora-java-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.core.annotationProcessorCommon)

    testImplementation(projects.config.configCommon)
    testImplementation(projects.scheduling.schedulingQuartz)
    testImplementation(projects.scheduling.schedulingJdk)
    testImplementation(projects.scheduling.schedulingDbScheduler)
    testImplementation(projects.config.configAnnotationProcessor)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
}
