plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    api(projects.resilient.resilientKoraDistributed)
    api(projects.redis.redisLettuce)

    testImplementation(projects.internal.testLogging)
    testImplementation(projects.internal.testRedis)
}
