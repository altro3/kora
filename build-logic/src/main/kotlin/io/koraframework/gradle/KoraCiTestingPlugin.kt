package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class KoraCiTestingPlugin : Plugin<Project> {
    override fun apply(rootProject: Project) {
        if (rootProject != rootProject.gradle.rootProject) {
            throw IllegalStateException("The CI Testing plugin must be applied to the root project only.")
        }

        val types = listOf("postgres", "cassandra", "redis", "kafka", "openapi", "codegen-java", "codegen-kotlin", "other")
        val prefixes = listOf("classes", "testClasses", "test", "javadoc")

        types.forEach { type ->
            rootProject.tasks.register("classes-$type") {
                group = "build"
                description = "Build classes for $type"
            }
            rootProject.tasks.register("testClasses-$type") {
                group = "build"
                description = "Build testClasses for $type"
            }
            rootProject.tasks.register("test-$type") {
                group = "verification"
                description = "Run tests with $type"
            }
            rootProject.tasks.register("javadoc-$type") {
                group = "documentation"
                description = "Javadoc for $type"
            }
        }

        val internalTestModules = hashSetOf(
            "test-cassandra",
            "test-kafka",
            "test-logging",
            "test-postgres",
            "test-redis"
        )

        rootProject.subprojects {
            val subproject = this

            subproject.plugins.withId("java") {
                val projectName = subproject.name

                if (subproject.childProjects.isNotEmpty() || projectName == "kora-bom") {
                    return@withId
                }

                if (internalTestModules.contains(projectName) && subproject.parent?.name == "internal") {
                    return@withId
                }

                fun getProjectFullName(pj: Project): String {
                    var fullName = pj.name
                    var parent = pj.parent
                    while (parent != null && parent.name != rootProject.name) {
                        fullName = "${parent.name}:$fullName"
                        parent = parent.parent
                    }
                    return fullName
                }

                val fullProjectName = getProjectFullName(subproject)

                val targetType = when {
                    fullProjectName == "database:database-common" ||
                            fullProjectName == "database:database-jdbc" ||
                            fullProjectName == "database:database-flyway" ||
                            fullProjectName == "database:database-liquibase" ||
                            fullProjectName == "experimental:camunda-engine-bpmn" -> "postgres"

                    fullProjectName == "database:database-cassandra" -> "cassandra"

                    fullProjectName == "redis:redis-lettuce" ||
                            fullProjectName == "cache:cache-redis-lettuce" -> "redis"

                    fullProjectName == "kafka:kafka" -> "kafka"

                    fullProjectName == "openapi:openapi-generator" ||
                            fullProjectName == "openapi:openapi-management" -> "openapi"

                    projectName.contains("annotation-processor") && fullProjectName != "mapping:mapstruct-java-extension" -> "codegen-java"

                    projectName.contains("symbol-processor") ||
                            projectName.contains("ksp") ||
                            fullProjectName == "mapping:mapstruct-ksp-extension" ||
                            fullProjectName == "mapping:konvert-ksp-extension" -> "codegen-kotlin"

                    else -> "other"
                }

                prefixes.forEach { prefix ->
                    rootProject.tasks.named("$prefix-$targetType") {
                        this.dependsOn(":$fullProjectName:$prefix")
                    }
                }
            }
        }
    }
}
