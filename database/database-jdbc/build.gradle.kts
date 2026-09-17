plugins {
    id("io.koraframework.kora-kotlin-module")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.database.databaseCommon)
    api(projects.core.common)
    api(libs.hikari)

    testImplementation(projects.internal.testPostgres)
}
