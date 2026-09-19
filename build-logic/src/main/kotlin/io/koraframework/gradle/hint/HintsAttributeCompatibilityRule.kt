package io.koraframework.gradle.hint

import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.Usage

abstract class HintsAttributeCompatibilityRule : org.gradle.api.attributes.AttributeCompatibilityRule<Usage> {
    override fun execute(details: CompatibilityCheckDetails<Usage>) {
        if (details.consumerValue?.name == "hintsElements") {
            val producer = details.producerValue?.name
            if (producer == Usage.JAVA_API || producer == Usage.JAVA_RUNTIME) {
                details.compatible()
            }
        }
    }
}
