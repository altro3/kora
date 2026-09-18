plugins {
    id("io.koraframework.kora-java-lib")
    id("io.koraframework.kora-in-test-generated")
}

dependencies {
    implementation(projects.aop.aopAnnotationProcessor)
    implementation(libs.javapoet)

    testImplementation(libs.prometheus.collector.caffeine)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(projects.internal.testLogging)
    testImplementation(projects.cache.cacheCaffeine)
    testImplementation(projects.cache.cacheRedisLettuce)
    testImplementation(projects.json.jsonCommon)
    testImplementation(projects.config.configCommon)
}
