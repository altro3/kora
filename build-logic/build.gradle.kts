plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
}

gradlePlugin {
    plugins {
        register("kora-java-module") {
            id = "io.koraframework.kora-java-module"
            implementationClass = "io.koraframework.gradle.KoraJavaModule"
        }

        register("kora-kotlin-module") {
            id = "io.koraframework.kora-kotlin-module"
            implementationClass = "io.koraframework.gradle.KoraKotlinModule"
        }
        register("kora-publishing") {
            id = "io.koraframework.kora-publishing"
            implementationClass = "io.koraframework.gradle.KoraPublishingPlugin"
        }
        register("kora-maven-publishing") {
            id = "io.koraframework.kora-maven-publishing"
            implementationClass = "io.koraframework.gradle.KoraMavenPublishingPlugin"
        }
        register("kora-ci-testing") {
            id = "io.koraframework.kora-ci-testing"
            implementationClass = "io.koraframework.gradle.KoraCiTestingPlugin"
        }
        register("kora-java-convention") {
            id = "io.koraframework.kora-java-convention"
            implementationClass = "io.koraframework.gradle.KoraJavaConventionPlugin"
        }
        register("kora-kotlin-convention") {
            id = "io.koraframework.kora-kotlin-convention"
            implementationClass = "io.koraframework.gradle.KoraKotlinConventionPlugin"
        }
        register("kora-test-convention") {
            id = "io.koraframework.kora-test-convention"
            implementationClass = "io.koraframework.gradle.KoraTestConventionPlugin"
        }
        register("kora-experimental-convention") {
            id = "io.koraframework.kora-experimental-convention"
            implementationClass = "io.koraframework.gradle.KoraExperimentalConventionPlugin"
        }
        register("kora-in-test-generated") {
            id = "io.koraframework.kora-in-test-generated"
            implementationClass = "io.koraframework.gradle.KoraInTestGeneratedPlugin"
        }
        register("kora-dependency-alignment") {
            id = "io.koraframework.kora-dependency-alignment"
            implementationClass = "io.koraframework.gradle.KoraDependencyAlignment"
        }
    }
}
