package io.koraframework.gradle.hint

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

@CacheableTask
abstract class MergeHintsTask : DefaultTask() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    @get:InputFiles
    abstract val hintFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    @TaskAction
    fun run() {
        val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
        val rootArray = mapper.createArrayNode()
        val targetFile = resultFile.get().asFile

        logger.lifecycle("=== [MergeHintsTask] Starting merge execution ===")
        logger.lifecycle("-> Total files in hintFiles collection: ${hintFiles.files.size}")

        hintFiles.files.forEach { file ->
            logger.lifecycle("-> Found input file path: ${file.absolutePath} (exists: ${file.exists()})")
        }

        var filesProcessed = 0

        hintFiles.files.forEach { file ->
            if (file.isFile && file.extension == "json") {
                filesProcessed++
                logger.lifecycle("-> Processing JSON file: ${file.name}")
                val node = mapper.readTree(file)
                if (node.isArray) {
                    node.forEach { rootArray.add(it) }
                } else {
                    rootArray.add(node)
                }
            } else {
                logger.lifecycle("-> Skipping input (not a JSON file): ${file.absolutePath}")
            }
        }

        logger.lifecycle("=== [MergeHintsTask] Total chunk files processed: $filesProcessed ===")

        Files.createDirectories(targetFile.parentFile.toPath())
        val jsonString = mapper.writeValueAsString(rootArray)
        targetFile.writeText(jsonString)

        logger.lifecycle("=== [MergeHintsTask] Target file written to: ${targetFile.absolutePath} ===")
    }
}
