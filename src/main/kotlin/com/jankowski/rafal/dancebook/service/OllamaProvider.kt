package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.OllamaProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service
class OllamaProvider(
    private val properties: OllamaProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : LlmProvider {

    private val log = LoggerFactory.getLogger(OllamaProvider::class.java)

    override val providerName: String = "ollama"

    override fun getModels(): List<String> {
        return properties.allowedModels
    }

    override fun callLlm(request: LlmRequest): LlmResponse {
        val model = request.model
        if (!properties.allowedModels.contains(model)) {
            throw IllegalArgumentException("Requested model is not on the allowed list of Ollama models: $model")
        }

        val cleanApiKey = properties.apiKey.trim().removeSurrounding("\"").removeSurrounding("'")
        if (cleanApiKey.isBlank()) {
            log.error("Ollama API key is blank/empty!")
            throw IllegalStateException("Ollama API key is not configured.")
        }

        val maskedKey = if (cleanApiKey.length > 12) {
            "${cleanApiKey.take(7)}...${cleanApiKey.takeLast(5)}"
        } else {
            "too-short-or-invalid"
        }
        log.info("Ingested Ollama API key. Length: {}, Masked: {}", cleanApiKey.length, maskedKey)
        log.info("Calling Ollama Cloud using model: {}", model)

        val options = mutableMapOf<String, Any>(
            "temperature" to (request.temperature ?: 1.0)
        )
        if (request.maxTokens != null) {
            options["num_predict"] = request.maxTokens
        }

        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to request.systemPrompt),
                mapOf("role" to "user", "content" to request.userPrompt)
            ),
            "stream" to false,
            "format" to "json",
            "options" to options
        )

        val requestBody = objectMapper.writeValueAsString(payload)

        // Native Ollama API chat endpoint
        val url = "${properties.baseUrl.removeSuffix("/")}/api/chat"

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $cleanApiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .build()

        try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            log.debug("Ollama API Response Status: {}, Body: {}", response.statusCode(), response.body())

            if (response.statusCode() != 200) {
                log.error("Ollama API call failed with status {}: {}", response.statusCode(), response.body())
                throw RuntimeException("Ollama API returned error status ${response.statusCode()}: ${response.body()}")
            }

            val responseNode = objectMapper.readTree(response.body())
            val doneReason = responseNode.path("done_reason")?.asText()
            if (doneReason == "length") {
                log.error("Ollama response was truncated due to token limit.")
                throw RuntimeException("Ollama response was truncated (done_reason=length).")
            }

            val messageNode = responseNode.path("message")
            val content = messageNode.path("content")?.asText()
                ?: throw RuntimeException("Could not extract content from Ollama response")

            val promptTokens = responseNode.path("prompt_eval_count").asInt(0)
            val completionTokens = responseNode.path("eval_count").asInt(0)
            val totalTokens = promptTokens + completionTokens

            return LlmResponse(
                content = cleanJsonContent(content),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                reasoningTokens = null
            )
        } catch (e: Exception) {
            log.error("Failed to execute Ollama LLM call", e)
            throw e
        }
    }

    private fun cleanJsonContent(content: String): String {
        val trimmed = content.trim()
        val firstBrace = trimmed.indexOf('{')
        val firstBracket = trimmed.indexOf('[')
        val startIndex = when {
            firstBrace == -1 -> firstBracket
            firstBracket == -1 -> firstBrace
            else -> minOf(firstBrace, firstBracket)
        }

        if (startIndex == -1) {
            return trimmed
        }

        val lastBrace = trimmed.lastIndexOf('}')
        val lastBracket = trimmed.lastIndexOf(']')
        val endIndex = maxOf(lastBrace, lastBracket)

        if (endIndex == -1 || endIndex < startIndex) {
            return trimmed
        }

        return trimmed.substring(startIndex, endIndex + 1)
    }
}
