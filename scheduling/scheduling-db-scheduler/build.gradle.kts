plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.scheduling.schedulingCommon)
    api(libs.db.scheduler)

    implementation(projects.logging.loggingCommon)
}
