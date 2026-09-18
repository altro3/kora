plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    compileOnly(libs.kotlin.stdlib.lib)

    api(projects.core.common)
    api(libs.jackson.core)
}

