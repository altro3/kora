plugins {
    id("io.koraframework.kora-java-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    api(projects.core.common)

    implementation(projects.config.configCommon)

    testImplementation(projects.internal.testLogging)
}
