plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.core.common)

    testImplementation(projects.internal.testLogging)
}
