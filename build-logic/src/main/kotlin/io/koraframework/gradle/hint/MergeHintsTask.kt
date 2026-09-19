package io.koraframework.gradle.hint

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

@CacheableTask
abstract class MergeHintsTask : DefaultTask() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    @get:SkipWhenEmpty
    @get:InputFiles
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
                .sortedBy { it.name }
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

        val jsonString = mapper.writeValueAsString(rootArray)

        Files.createDirectories(targetFile.parentFile.toPath())
        targetFile.writeText(jsonString)

        logger.info("=== [MergeHintsTask] Final file successfully written to: ${targetFile.absolutePath} ===")
    }
}
