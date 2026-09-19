version = System.getenv().getOrDefault("KORA_VERSION", "2.0.0-SNAPSHOT")
group = "io.koraframework"

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.koraframework.kora-root-publish")
    id("io.koraframework.kora-root-coverage")
}
