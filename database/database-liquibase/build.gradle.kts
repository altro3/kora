plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.database.databaseJdbc)
    api(libs.liquibase)

    testImplementation(projects.internal.testPostgres)
}
