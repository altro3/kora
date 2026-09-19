val koraVersion = System.getenv().getOrDefault("KORA_VERSION", "2.0.0-SNAPSHOT")
val koraGroup = "io.koraframework"

version = koraVersion
group = koraGroup

ext["globalVersion"] = koraVersion
ext["globalGroup"] = koraGroup

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.koraframework.kora-root-publish")
    id("io.koraframework.kora-root-coverage")
}
