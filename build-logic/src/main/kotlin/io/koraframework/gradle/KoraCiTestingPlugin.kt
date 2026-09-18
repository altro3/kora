package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class KoraCiTestingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootProject = project.gradle.rootProject

        if (project == rootProject) {
            val types = listOf("postgres", "cassandra", "redis", "kafka", "openapi", "codegen-java", "codegen-kotlin", "other")
            types.forEach { type ->
                project.tasks.register("classes-$type") { group = "build"; description = "Build classes for $type" }
                project.tasks.register("testClasses-$type") { group = "build"; description = "Build testClasses for $type" }
                project.tasks.register("test-$type") { group = "verification"; description = "Run tests with $type" }
                project.tasks.register("javadoc-$type") { group = "documentation"; description = "Javadoc for $type" }
            }
            return
        }

        project.plugins.withId("java") {
            val projectName = project.name

            if (project.childProjects.isNotEmpty() || projectName == "kora-bom") {
                return@withId
            }

            if (project.path.startsWith(":internal:") && (projectName == "test-cassandra" || projectName == "test-kafka" || projectName == "test-logging" || projectName == "test-postgres" || projectName == "test-redis")) {
                return@withId
            }

            val fullProjectName = project.path.removePrefix(":")

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

            val prefixes = listOf("classes", "testClasses", "test", "javadoc")
            prefixes.forEach { prefix ->
                rootProject.tasks.named("$prefix-$targetType") {
                    dependsOn(project.tasks.named(prefix))
                }
            }
        }
    }
}
