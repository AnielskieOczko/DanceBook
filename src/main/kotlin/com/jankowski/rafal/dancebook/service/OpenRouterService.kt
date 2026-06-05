package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.OpenRouterProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service
class OpenRouterService(
    private val properties: OpenRouterProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) {
    private val log = LoggerFactory.getLogger(OpenRouterService::class.java)

    fun getModels(): List<String> {
        return properties.allowedFreeModels
    }

    fun callLlm(systemPrompt: String, userPrompt: String, requestedModel: String? = null): String {
        val model = requestedModel ?: properties.defaultModel
        if (!properties.allowedFreeModels.contains(model)) {
            throw IllegalArgumentException("Requested model is not on the allowed list of free models: $model")
        }

        val cleanApiKey = properties.apiKey.trim().removeSurrounding("\"").removeSurrounding("'")
        if (cleanApiKey.isBlank()) {
            log.error("OpenRouter API key is blank/empty!")
            throw IllegalStateException("OpenRouter API key is not configured.")
        }

        val maskedKey = if (cleanApiKey.length > 12) {
            "${cleanApiKey.take(7)}...${cleanApiKey.takeLast(5)}"
        } else {
            "too-short-or-invalid"
        }
        log.info("Ingested OpenRouter API key. Length: {}, Masked: {}", cleanApiKey.length, maskedKey)

        log.info("Calling OpenRouter LLM using model: {}", model)

        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "response_format" to mapOf("type" to "json_object")
        )

        val requestBody = objectMapper.writeValueAsString(payload)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $cleanApiKey")
            .header("HTTP-Referer", "https://github.com/apify/agent-skills")
            .header("X-Title", "DanceBook Figures Parser")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.error("OpenRouter API call failed with status {}: {}", response.statusCode(), response.body())
                throw RuntimeException("OpenRouter API returned error status: ${response.statusCode()}")
            }

            val responseNode = objectMapper.readTree(response.body())
            val messageNode = responseNode.path("choices").get(0)?.path("message")
            val content = messageNode?.path("content")?.asText()
                ?: throw RuntimeException("Could not extract content from LLM response choice")

            return cleanJsonContent(content)
        } catch (e: Exception) {
            log.error("Failed to execute OpenRouter LLM call", e)
            throw e
        }
    }

    private fun cleanJsonContent(content: String): String {
        var trimmed = content.trim()
        if (trimmed.startsWith("```")) {
            val firstLineEnd = trimmed.indexOf('\n')
            if (firstLineEnd != -1) {
                trimmed = trimmed.substring(firstLineEnd).trim()
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length - 3).trim()
            }
        }
        return trimmed
    }
}
