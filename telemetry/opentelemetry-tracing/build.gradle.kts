plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.config.configCommon)
    api(projects.telemetry.opentelemetryCommon)
    api(libs.opentelemetry.sdk.trace)
}
