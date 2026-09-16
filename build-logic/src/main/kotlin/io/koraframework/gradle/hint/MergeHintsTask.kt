package io.koraframework.gradle.hint

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

abstract class MergeHintsTask : DefaultTask() {

    @get:InputDirectory
    abstract val partsDirectory: DirectoryProperty

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    @TaskAction
    fun run() {
        val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
        val rootArray = mapper.createArrayNode()

        val partsDirFile = partsDirectory.get().asFile
        val targetFile = resultFile.get().asFile

        println("=== [MergeHintsTask] Начинаем сборку финального kora-hints.json ===")

        if (partsDirFile.exists()) {
            partsDirFile.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .forEach { file ->
                    println("-> Мерджим файл: ${file.name}")
                    val node = mapper.readTree(file)
                    if (node.isArray) {
                        node.forEach { rootArray.add(it) }
                    } else {
                        rootArray.add(node)
                    }
                }
        }

        val jsonString = mapper.writeValueAsString(rootArray)

        if (targetFile.exists()) {
            if (targetFile.readText() == jsonString) {
                println("=== [MergeHintsTask] Контент не изменился, пропускаем запись ===")
                return
            }
            targetFile.delete()
        }

        Files.createDirectories(targetFile.parentFile.toPath())
        targetFile.createNewFile()
        targetFile.writeText(jsonString)

        println("=== [MergeHintsTask] Финальный файл успешно записан! ===")
    }
}
