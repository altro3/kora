plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.koraframework.kora-publishing")
    id("io.koraframework.kora-maven-publishing")
    id("io.koraframework.kora-ci-testing")
    jacoco
}
