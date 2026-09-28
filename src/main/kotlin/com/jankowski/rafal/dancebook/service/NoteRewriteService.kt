package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.OpenRouterProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NoteRewriteService(
    private val llmProviderRouter: LlmProviderRouter,
    private val richTextService: RichTextService,
    private val openRouterProperties: OpenRouterProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(NoteRewriteService::class.java)

    /**
     * Returns true when an LLM provider is configured and can be used for rewrites.
     * Currently gated on the OpenRouter API key being present.
     */
    fun isAvailable(): Boolean = openRouterProperties.apiKey.isNotBlank()

    /**
     * Calls the LLM to produce a rewritten version of [rawDescription].
     * The input is first cleaned with [RichTextService.clean] to ensure it contains
     * valid text and to preserve permitted markup (including links and formatting).
     * The result is sanitised with [RichTextService.clean] before being returned,
     * so only the allowed markup (bold, italic, links, lists) survives.
     *
     * Returns the sanitised HTML string, or throws on provider failure / timeout.
     */
    fun rewrite(rawDescription: String): String {
        val cleanInput = richTextService.clean(rawDescription)
            ?: throw IllegalArgumentException("Note text must not be blank")

        val systemPrompt = """
            You are a writing assistant helping a dancer clean up rough class notes.
            
            Rewrite the HTML text the user gives you to improve clarity and structure.
            Keep every fact exactly as written: counts, timings, figure names, person names, and the original language.
            Do not add, remove, or invent any information.
            
            Format using only: bold (<strong>), italic (<em>), bulleted lists (<ul><li>), numbered lists (<ol><li>), paragraphs (<p>), and links (<a href="...">).
            Every link's href and text must be kept exactly as they were in the original text.
            Do not use headings, tables, images, or any other HTML elements.
            
            Respond with a single JSON object in exactly this shape:
            {"rewrite": "<your rewritten HTML here>"}
            
            Do not include anything outside that JSON object.
        """.trimIndent()

        log.info("Requesting note rewrite via OpenRouter, model={}", openRouterProperties.defaultModel)

        val llmResponse = llmProviderRouter.callLlm(
            provider = "openrouter",
            request = LlmRequest(
                systemPrompt = systemPrompt,
                userPrompt = cleanInput,
                model = openRouterProperties.defaultModel,
                maxTokens = 2048,
                temperature = 0.4
            )
        )

        val rawHtml = extractRewriteField(llmResponse.content)
        return richTextService.clean(rawHtml)
            ?: throw IllegalStateException("Sanitised rewrite is empty")
    }

    private fun extractRewriteField(content: String): String {
        val rootNode = try {
            objectMapper.readTree(content)
        } catch (e: Exception) {
            null
        }

        if (rootNode != null && rootNode.isObject) {
            val rewriteNode = rootNode.get("rewrite")
            val rewriteText = rewriteNode?.asText()
            if (rewriteText.isNullOrBlank()) {
                throw IllegalStateException("LLM returned empty or missing rewrite field")
            }
            return rewriteText
        }

        if (rootNode == null) {
            log.warn("Could not parse LLM response as JSON, treating as raw HTML")
            return content
        }

        throw IllegalStateException("LLM response was JSON but not an object with a rewrite field")
    }
}
