plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.core.common)
    api(projects.logging.loggingCommon)
    api(libs.logback.classic) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}
