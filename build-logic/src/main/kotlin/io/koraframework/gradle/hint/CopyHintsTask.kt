package io.koraframework.gradle.hint

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

@CacheableTask
abstract class CopyHintsTask : DefaultTask() {

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    abstract val hintFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val targetDir = outputDirectory.get().asFile
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        logger.info("=== [CopyHintsTask] Starting safe hint file copy ===")

        var counter = 0

        hintFiles.files.forEach { file ->
            counter++

            val moduleDirName = file.parentFile?.parentFile?.parentFile?.parentFile?.name ?: "unknown-$counter"
            val newFileName = "module-hint-$moduleDirName.json"

            logger.info("-> FOUND [#$counter]: ${file.absolutePath} -> Copying as: $newFileName")

            fileSystemOperations.copy {
                from(file)
                into(targetDir)
                rename { newFileName }
            }
        }

        logger.info("=== [CopyHintsTask] Successfully copied files: $counter ===")

        if (counter == 0) {
            error("CRITICAL ERROR: No kora-module-hints.json files were found!")
        }
    }
}
