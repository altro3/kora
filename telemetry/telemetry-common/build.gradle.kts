plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.config.configCommon)
    api(libs.opentelemetry.semconv)
    api(libs.opentelemetry.semconv.incubating)
}
