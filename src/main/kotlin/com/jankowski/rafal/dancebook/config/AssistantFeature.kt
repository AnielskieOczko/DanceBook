package com.jankowski.rafal.dancebook.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

/**
 * Marks a bean that exists only when a chat model is configured, i.e. `GOOGLE_AI_API_KEY` is
 * set. Everything the assistant owns carries it, so without a key there are no beans, no
 * routes (a request 404s) and, because [AssistantFeature] reads the same key, no markup.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("!'\${google.ai.api-key:}'.trim().isEmpty()")
annotation class ConditionalOnAssistant

/** The same rule as [ConditionalOnAssistant], for code that must ask instead of being absent. */
@Component
class AssistantFeature(googleAi: GoogleAiProperties) {
    val enabled: Boolean = googleAi.apiKey.isNotBlank()
}
