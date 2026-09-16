plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
}

gradlePlugin {
    plugins {
        register("kora-java-lib") {
            id = "io.koraframework.kora-java-lib"
            implementationClass = "io.koraframework.gradle.KoraJavaLib"
        }

        register("kora-kotlin-lib") {
            id = "io.koraframework.kora-kotlin-lib"
            implementationClass = "io.koraframework.gradle.KoraKotlinLib"
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
        register("kora-test-convention") {
            id = "io.koraframework.kora-test-convention"
            implementationClass = "io.koraframework.gradle.KoraTestConventionPlugin"
        }
        register("kora-experimental-convention") {
            id = "io.koraframework.kora-experimental-convention"
            implementationClass = "io.koraframework.gradle.KoraExperimentalConventionPlugin"
        }
        register("kora-test-generated") {
            id = "io.koraframework.kora-test-generated"
            implementationClass = "io.koraframework.gradle.KoraTestGeneratedPlugin"
        }
        register("kora-dependency-alignment") {
            id = "io.koraframework.kora-dependency-alignment"
            implementationClass = "io.koraframework.gradle.KoraDependencyAlignment"
        }
    }
}
