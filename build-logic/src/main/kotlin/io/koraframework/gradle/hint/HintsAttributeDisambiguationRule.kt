package io.koraframework.gradle.hint

import org.gradle.api.attributes.Usage

abstract class HintsAttributeDisambiguationRule : org.gradle.api.attributes.AttributeDisambiguationRule<Usage> {
    override fun execute(details: org.gradle.api.attributes.MultipleCandidatesDetails<Usage>) {
        if (details.consumerValue?.name == "hintsElements") {
            val candidateNames = details.candidateValues.map { it.name }

            if (candidateNames.contains("hintsElements")) {
                details.closestMatch(details.candidateValues.first { it.name == "hintsElements" })
            } else if (candidateNames.contains(Usage.JAVA_API)) {
                details.closestMatch(details.candidateValues.first { it.name == Usage.JAVA_API })
            }
        }
    }
}
