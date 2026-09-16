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

        logger.info("=== [MergeHintsTask] Starting configuration of final kora-hints.json ===")

        var filesProcessed = 0
        if (partsDirFile.exists()) {
            partsDirFile.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .forEach { file ->
                    filesProcessed++
                    val node = mapper.readTree(file)

                    if (node.isArray) {
                        logger.info("-> Merging array from file: ${file.name} (elements: ${node.size()})")
                        node.forEach { rootArray.add(it) }
                    } else {
                        logger.info("-> Merging object from file: ${file.name}")
                        rootArray.add(node)
                    }
                }
        }

        logger.info("=== [MergeHintsTask] Total chunk files processed: $filesProcessed ===")
        logger.info("=== [MergeHintsTask] Final array contains elements: ${rootArray.size()} ===")

        val jsonString = mapper.writeValueAsString(rootArray)

        if (targetFile.exists()) {
            if (targetFile.readText() == jsonString) {
                logger.info("=== [MergeHintsTask] Content has not changed, skipping write ===")
                return
            }
            targetFile.delete()
        }

        Files.createDirectories(targetFile.parentFile.toPath())
        targetFile.createNewFile()
        targetFile.writeText(jsonString)

        logger.info("=== [MergeHintsTask] Final file successfully written to: ${targetFile.absolutePath} ===")
    }
}
