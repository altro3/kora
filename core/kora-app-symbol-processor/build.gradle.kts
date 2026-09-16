import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

plugins {
    id("io.koraframework.kora-kotlin-lib")
    id("io.koraframework.kora-in-test-generated")
    `java-test-fixtures`
}

dependencies {
    api(projects.core.symbolProcessorCommon)
    implementation(libs.jackson.core)

    testImplementation(projects.json.jsonCommon)
    testImplementation(testFixtures(projects.core.annotationProcessorCommon))
    testImplementation(testFixtures(projects.core.symbolProcessorCommon))
}

val copyHints = tasks.register<Copy>("copyHints") {
    var counter = 0
    from(project.rootDir) {
        includeEmptyDirs = false
        include("**/src/main/resources/kora-module-hints.json")
        exclude("**/build/**/*")
        rename {
            counter++
            "module-hint-$counter.json"
        }
        eachFile {
            path = name
        }
    }
    into(layout.buildDirectory.dir("kora-hints/parts"))
}

val buildHints = tasks.register("buildHints") {
    dependsOn(copyHints)

    val buildDir = layout.buildDirectory
    val outputDir = buildDir.dir("kora-hints")
    val resultFile = outputDir.get().file("kora-hints.json").asFile
    val partsDir = buildDir.dir("kora-hints/parts").get().asFile

    inputs.dir(partsDir)
    outputs.dir(outputDir)

    doLast {
        val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
        val combinedList = mutableListOf<Any>()

        // Рекурсивно обходим файлы во временной папке parts
        fileTree(partsDir).matching { include("*.json") }.forEach { file ->
            val parsedJson = mapper.readValue(file, Any::class.java)
            if (parsedJson is List<*>) {
                combinedList.addAll(parsedJson.filterNotNull())
            } else {
                combinedList.add(parsedJson)
            }
        }

        val jsonString = mapper.writeValueAsString(combinedList)

        if (resultFile.exists()) {
            if (resultFile.readText() == jsonString) {
                return@doLast
            }
            resultFile.delete()
        }

        Files.createDirectories(resultFile.parentFile.toPath())
        resultFile.createNewFile()
        resultFile.writeText(jsonString)
    }
}

sourceSets {
    main {
        resources {
            srcDir(buildHints)
        }
    }
}
