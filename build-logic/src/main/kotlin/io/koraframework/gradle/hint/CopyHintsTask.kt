package io.koraframework.gradle.hint

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Copy

@CacheableTask
abstract class CopyHintsTask : Copy() {
    init {
        group = "publishing"
        description = "Safely copies and flattens kora hint files"

        includeEmptyDirs = false

        eachFile {
            val uniqueName = file.absolutePath.hashCode().toString().replace("-", "m")
            path = "module-hint-$uniqueName.json"
        }
    }
}
