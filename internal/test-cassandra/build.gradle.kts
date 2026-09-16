plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(libs.testcontainers.cassandra) {
        exclude(group = "com.datastax.cassandra", module = "cassandra-driver-core")
    }
    api(libs.cassandra.driver)

    implementation(libs.jspecify)
}
