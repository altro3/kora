package io.koraframework.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

class KoraCiTestingConventionPlugin : Plugin<Project> {
    override fun apply(rootProject: Project) {
        if (rootProject != rootProject.gradle.rootProject) {
            throw IllegalStateException("The CI Testing plugin must be applied to the root project only.")
        }

        val nonOtherModules = hashSetOf(
            "internal:test-cassandra",
            "internal:test-kafka",
            "internal:test-logging",
            "internal:test-postgres",
            "internal:test-redis"
        )

        fun getProjectFullName(pj: Project): String {
            var fullName = pj.name
            var parent = pj.parent
            while (parent != null && parent.name != rootProject.name) {
                fullName = "${parent.name}:$fullName"
                parent = parent.parent
            }
            return fullName
        }

        fun addDependencies(taskProviders: List<TaskProvider<Task>>, dependency: String) {
            taskProviders.forEach { taskProvider ->
                taskProvider.configure {
                    val taskPrefix = this.name.split("-")[0]
                    this.dependsOn(":$dependency:$taskPrefix")
                }
            }
            nonOtherModules.add(dependency)
        }

        fun addDependenciesByPattern(taskProviders: List<TaskProvider<Task>>, namePattern: String) {
            rootProject.allprojects.forEach { project ->
                if (project.name != rootProject.name && project.name.contains(namePattern) && !nonOtherModules.contains(project.name)) {
                    val fullName = getProjectFullName(project)
                    taskProviders.forEach { taskProvider ->
                        taskProvider.configure {
                            val taskPrefix = this.name.split("-")[0]
                            this.dependsOn(":$fullName:$taskPrefix")
                        }
                    }
                    nonOtherModules.add(fullName)
                }
            }
        }

        fun createTasks(type: String): List<TaskProvider<Task>> {
            val classesByType = rootProject.tasks.register("classes-$type") {
                group = "build"
                description = "Build classes for $type"
            }

            val testClassesByType = rootProject.tasks.register("testClasses-$type") {
                group = "build"
                description = "Build testClasses for $type"
            }

            val testByType = rootProject.tasks.register("test-$type") {
                group = "verification"
                description = "Run tests with $type"
            }

            val javadocByType = rootProject.tasks.register("javadoc-$type") {
                group = "documentation"
                description = "Javadoc for $type"
            }

            return listOf(classesByType, testClassesByType, testByType, javadocByType)
        }

        val tasksPostgres = createTasks("postgres")
        val tasksCassandra = createTasks("cassandra")
        val tasksRedis = createTasks("redis")
        val tasksKafka = createTasks("kafka")
        val tasksOpenapi = createTasks("openapi")
        val tasksCodegenJava = createTasks("codegen-java")
        val tasksCodegenKotlin = createTasks("codegen-kotlin")
        val tasksOther = createTasks("other")

        rootProject.gradle.projectsEvaluated {
            // Postgres
            addDependencies(tasksPostgres, "database:database-common")
            addDependencies(tasksPostgres, "database:database-jdbc")
            addDependencies(tasksPostgres, "database:database-flyway")
            addDependencies(tasksPostgres, "database:database-liquibase")
            addDependencies(tasksPostgres, "experimental:camunda-engine-bpmn")

            // Cassandra
            addDependencies(tasksCassandra, "database:database-cassandra")

            // Redis
            addDependencies(tasksRedis, "redis:redis-lettuce")
            addDependencies(tasksRedis, "cache:cache-redis-lettuce")

            // Kafka
            addDependencies(tasksKafka, "kafka:kafka")

            // OpenAPI
            addDependencies(tasksOpenapi, "openapi:openapi-generator")
            addDependencies(tasksOpenapi, "openapi:openapi-management")

            // Codegen Java
            addDependenciesByPattern(tasksCodegenJava, "annotation-processor")
            addDependencies(tasksCodegenJava, "mapping:mapstruct-java-extension")

            // Codegen Kotlin
            addDependenciesByPattern(tasksCodegenKotlin, "symbol-processor")
            addDependenciesByPattern(tasksCodegenKotlin, "ksp")
            addDependencies(tasksCodegenKotlin, "mapping:mapstruct-ksp-extension")
            addDependencies(tasksCodegenKotlin, "mapping:konvert-ksp-extension")

            rootProject.allprojects.forEach { project ->
                if (project.name != rootProject.name && project.name != "kora-bom" && project.childProjects.isEmpty()) {
                    val fullName = getProjectFullName(project)
                    if (!nonOtherModules.contains(fullName)) {
                        addDependencies(tasksOther, fullName)
                    }
                }
            }
        }
    }
}
