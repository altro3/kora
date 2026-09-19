package io.koraframework.gradle.hint

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Copy
import java.util.concurrent.atomic.AtomicInteger

@CacheableTask
abstract class CopyHintsTask : Copy() {
    init {
        group = "publishing"
        description = "Safely copies and flattens kora hint files"

        includeEmptyDirs = false

        val counter = AtomicInteger(0)
        rename {
            "module-hint-${counter.incrementAndGet()}.json"
        }

        eachFile {
            path = name
        }
    }
}
