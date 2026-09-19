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
            implementationClass = "io.koraframework.gradle.KoraJavaModuleConventionPlugin"
        }
        register("kora-kotlin-module") {
            id = "io.koraframework.kora-kotlin-module"
            implementationClass = "io.koraframework.gradle.KoraKotlinModuleConventionPlugin"
        }
        register("kora-java") {
            id = "io.koraframework.kora-java"
            implementationClass = "io.koraframework.gradle.KoraJavaConventionPlugin"
        }
        register("kora-kotlin") {
            id = "io.koraframework.kora-kotlin"
            implementationClass = "io.koraframework.gradle.KoraKotlinConventionPlugin"
        }
        register("kora-dependency-alignment") {
            id = "io.koraframework.kora-dependency-alignment"
            implementationClass = "io.koraframework.gradle.KoraDependencyAlignmentConventionPlugin"
        }
        register("kora-publishing") {
            id = "io.koraframework.kora-publishing"
            implementationClass = "io.koraframework.gradle.publish.KoraPublishingConventionPlugin"
        }
        register("kora-maven-publishing") {
            id = "io.koraframework.kora-maven-publishing"
            implementationClass = "io.koraframework.gradle.KoraMavenPublishingConventionPlugin"
        }
        register("kora-experimental") {
            id = "io.koraframework.kora-experimental"
            implementationClass = "io.koraframework.gradle.KoraExperimentalConventionPlugin"
        }
        register("kora-soap") {
            id = "io.koraframework.kora-soap"
            implementationClass = "io.koraframework.gradle.soap.KoraSoapConventionPlugin"
        }
        register("kora-hints") {
            id = "io.koraframework.kora-hints"
            implementationClass = "io.koraframework.gradle.hint.KoraHintsConventionPlugin"
        }
        register("kora-ci-testing") {
            id = "io.koraframework.kora-ci-testing"
            implementationClass = "io.koraframework.gradle.test.KoraCiTestingConventionPlugin"
        }
        register("kora-test") {
            id = "io.koraframework.kora-test"
            implementationClass = "io.koraframework.gradle.test.KoraTestConventionPlugin"
        }
        register("kora-coverage") {
            id = "io.koraframework.kora-coverage"
            implementationClass = "io.koraframework.gradle.test.KoraCoverageConventionPlugin"
        }
        register("kora-in-test-generated") {
            id = "io.koraframework.kora-in-test-generated"
            implementationClass = "io.koraframework.gradle.test.KoraInTestGeneratedConventionPlugin"
        }
    }
}
