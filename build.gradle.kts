@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.koraframework.kora-publishing")
    `jacoco-report-aggregation`
}

reporting {
    reports {
        create<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName.set("kora-full")
        }
    }
}
