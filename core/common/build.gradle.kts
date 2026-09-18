plugins {
    id("io.koraframework.kora-java-lib")
}

dependencies {
    api(projects.core.applicationGraph)
    api(projects.telemetry.micrometerCommon)
    api(libs.slf4j.api)
    api(libs.opentelemetry.api)
}

val generateKoraVersion = tasks.register("generateKoraVersion") {
    val versionProvider = project.provider { project.version.toString() }
    val nameProvider = project.provider { project.name }

    inputs.property("projectName", nameProvider)
    inputs.property("projectVersion", versionProvider)

    val outputDir = layout.buildDirectory.dir("kora-version")
    outputs.dir(outputDir)

    doLast {
        val projectName = nameProvider.get()
        val projectVersion = versionProvider.get()
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
