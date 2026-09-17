plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.database.databaseJdbc)
    api(libs.liquibase)

    testImplementation(projects.internal.testPostgres)
}
