plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
}

gradlePlugin {
    plugins {
        create("kora-publishing") {
            id = "io.koraframework.kora-publishing"
            implementationClass = "io.koraframework.gradle.KoraPublishingPlugin"
        }
    }
}
