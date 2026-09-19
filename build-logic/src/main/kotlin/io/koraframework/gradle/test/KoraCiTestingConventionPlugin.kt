package io.koraframework.gradle.test

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
            "internal",
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

        fun linkCiTaskToSubproject(ciTaskProvider: TaskProvider<Task>, subproject: Project) {
            ciTaskProvider.configure {
                val taskPrefix = this.name.split("-")[0]
                this.dependsOn(subproject.tasks.matching { it.name == taskPrefix })
            }
        }

        fun addDependencies(taskProviders: List<TaskProvider<Task>>, dependency: String) {
            val subproject = rootProject.findProject(":$dependency")
            if (subproject != null) {
                taskProviders.forEach { linkCiTaskToSubproject(it, subproject) }
            }
            nonOtherModules.add(dependency)
        }

        fun addDependenciesByPattern(taskProviders: List<TaskProvider<Task>>, namePattern: String) {
            rootProject.subprojects {
                if (name != rootProject.name && name.contains(namePattern) && !nonOtherModules.contains(name)) {
                    val fullName = getProjectFullName(this)
                    taskProviders.forEach { linkCiTaskToSubproject(it, this) }
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

        rootProject.subprojects {
            afterEvaluate {
                if (name != rootProject.name && name != "kora-bom" && childProjects.isEmpty()) {
                    val fullName = getProjectFullName(this)
                    if (!nonOtherModules.contains(fullName)) {
                        tasksOther.forEach { linkCiTaskToSubproject(it, this) }
                    }
                }
            }
        }
    }
}
