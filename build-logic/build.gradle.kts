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
        create("kora-java-convention") {
            id = "io.koraframework.kora-java-convention"
            implementationClass = "io.koraframework.gradle.KoraJavaConventionPlugin"
        }
        create("kora-test-convention") {
            id = "io.koraframework.kora-test-convention"
            implementationClass = "io.koraframework.gradle.KoraTestConventionPlugin"
        }
        create("kora-experimental-convention") {
            id = "io.koraframework.kora-experimental-convention"
            implementationClass = "io.koraframework.gradle.KoraExperimentalConventionPlugin"
        }
        create("kora-maven-publishing") {
            id = "io.koraframework.kora-maven-publishing"
            implementationClass = "io.koraframework.gradle.KoraMavenPublishingPlugin"
        }
    }
}
