plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(libs.testcontainers.kafka)
    api(libs.kafka.client)

    implementation(libs.jspecify)
}
