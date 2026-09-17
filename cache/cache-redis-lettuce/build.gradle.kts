plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    annotationProcessor(projects.config.configAnnotationProcessor)

    api(projects.cache.cacheRedisCommon)
    api(projects.redis.redisLettuce)

    testImplementation(projects.internal.testLogging)
    testImplementation(projects.internal.testRedis)
}
