package io.koraframework.gradle.hint

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject
import java.io.File

abstract class CopyHintsTask : DefaultTask() {

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    val hintFiles: FileCollection
        get() = project.files(repositoryRoot.asFileTree.matching {
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

        println("=== [CopyHintsTask] Начинаем безопасный поиск хинтов через FileCollection ===")

        var counter = 0

        hintFiles.files.forEach { file ->
            counter++

            val moduleDirName = file.parentFile?.parentFile?.parentFile?.parentFile?.name ?: "unknown-$counter"
            val newFileName = "module-hint-$moduleDirName.json"

            println("-> НАЙДЕНО [№$counter]: ${file.absolutePath} -> Копируем как: $newFileName")

            fileSystemOperations.copy {
                from(file)
                into(targetDir)
                rename { newFileName }
            }
        }

        println("=== [CopyHintsTask] Успешно скопировано файлов-кусочков: $counter ===")

        if (counter == 0) {
            error("КРИТИЧЕСКАЯ ОШИБКА: Ни один файл kora-module-hints.json не был найден!")
        }
    }
}
