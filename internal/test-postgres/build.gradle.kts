plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(libs.testcontainers.postgresql)
    api(libs.jdbc.postgresql)

    implementation(libs.jspecify)
}
