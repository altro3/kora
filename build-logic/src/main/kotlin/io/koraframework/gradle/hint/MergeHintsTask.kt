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

        var filesProcessed = 0

        hintFiles.files.forEach { file ->
            if (file.isFile && file.extension == "json") {
                filesProcessed++
                val node = mapper.readTree(file)
                if (node.isArray) {
                    node.forEach { rootArray.add(it) }
                } else {
                    rootArray.add(node)
                }
            }
        }

        Files.createDirectories(targetFile.parentFile.toPath())
        val jsonString = mapper.writeValueAsString(rootArray)
        targetFile.writeText(jsonString)
    }
}
