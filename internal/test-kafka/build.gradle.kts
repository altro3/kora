plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    api(libs.testcontainers.kafka)
    api(libs.kafka.client)

    implementation(libs.jspecify)
}
