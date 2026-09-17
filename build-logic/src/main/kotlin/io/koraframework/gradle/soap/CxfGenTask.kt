package io.koraframework.gradle.soap

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

abstract class CxfGenTask : DefaultTask() {

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:org.gradle.work.Incremental
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:CompileClasspath
    @get:InputFiles
    abstract val cxfClasspath: ConfigurableFileCollection

    @TaskAction
    fun execute() {
        val queue = workerExecutor.classLoaderIsolation {
            classpath.from(cxfClasspath)
        }

        queue.submit(CxfRunnable::class.java) {
            args.set(
                listOf(
                    "-d", outputDir.get().asFile.absolutePath,
                    "-autoNameResolution",
                    "-verbose",
                    inputFile.get().asFile.absolutePath
                )
            )
        }
    }
}
