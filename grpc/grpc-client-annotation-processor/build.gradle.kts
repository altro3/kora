import com.google.protobuf.gradle.id

plugins {
    id("io.koraframework.kora-java-module")
    id("io.koraframework.kora-in-test-generated")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(projects.core.annotationProcessorCommon)

    implementation(projects.core.koraAppAnnotationProcessor)

    testImplementation(libs.grpc.java.gen)
    testImplementation(libs.grpc.protobuf)
    testImplementation(libs.protobuf.java)
    testImplementation(projects.grpc.grpcClient)
    testImplementation(projects.config.configAnnotationProcessor)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(libs.javax.annotation.api)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.orNull?.toString()
    }
    plugins {
        id("grpc") {
            artifact = libs.grpc.java.gen.orNull?.toString()
        }
    }
    generateProtoTasks {
        ofSourceSet("test").forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

sourceSets {
    test {
        java {
            val buildDir = layout.buildDirectory
            srcDir(buildDir.dir("generated/source/proto/test/grpc"))
            srcDir(buildDir.dir("generated/source/proto/test/java"))
        }
    }
}
