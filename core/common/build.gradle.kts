plugins {
    id("io.koraframework.kora-java-module")
}

dependencies {
    api(projects.core.applicationGraph)
    api(projects.telemetry.micrometerCommon)
    api(libs.slf4j.api)
    api(libs.opentelemetry.api)
}

val generateKoraVersion = tasks.register("generateKoraVersion") {
    val buildDir = layout.buildDirectory
    val projectName = project.name
    val projectVersion = project.version.toString()

    val outputDir = buildDir.dir("kora-version")
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("META-INF/kora/version/$projectName").asFile
        file.parentFile.mkdirs()
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()
        file.writeText(projectVersion)
    }
}

sourceSets {
    main {
        resources {
            srcDir(generateKoraVersion)
        }
    }
}

tasks.processResources {
    dependsOn(generateKoraVersion)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateKoraVersion)
}
