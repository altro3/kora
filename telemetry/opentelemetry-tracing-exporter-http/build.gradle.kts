plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.telemetry.opentelemetryTracing)
    api(libs.opentelemetry.exporter.sender.jdk)
    api(libs.opentelemetry.exporter.otlp) {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
}
