plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    api(projects.core.common)

    testImplementation(projects.internal.testLogging)
}
