plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    api(libs.testcontainers.postgresql)
    api(libs.jdbc.postgresql)

    implementation(libs.jspecify)
}
