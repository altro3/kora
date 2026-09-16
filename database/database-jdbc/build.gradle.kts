plugins {
    id("io.koraframework.kora-kotlin-lib")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.database.databaseCommon)
    api(projects.core.common)
    api(libs.hikari)

    testImplementation(projects.internal.testPostgres)
}
