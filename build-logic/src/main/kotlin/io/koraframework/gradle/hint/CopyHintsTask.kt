package io.koraframework.gradle.hint

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

abstract class CopyHintsTask : DefaultTask() {

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val objectFactory: ObjectFactory

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    val hintFiles: FileCollection
        get() = objectFactory.fileCollection().from(repositoryRoot.asFileTree.matching {
            include("**/src/main/resources/kora-module-hints.json")
            exclude("**/build/**/*")
        })

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val targetDir = outputDirectory.get().asFile

        targetDir.deleteRecursively()
        targetDir.mkdirs()

        logger.info("=== [CopyHintsTask] Starting safe hint file search via ObjectFactory ===")

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
