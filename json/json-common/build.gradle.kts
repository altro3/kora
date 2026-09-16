plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.core.common)
    api(libs.jackson.core)

    compileOnly(libs.kotlin.stdlib.lib)
}

