package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service
class GoogleAiProvider(
    private val properties: GoogleAiProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : LlmProvider {

    private val log = LoggerFactory.getLogger(GoogleAiProvider::class.java)

    override val providerName: String = "google-ai"

    override fun getModels(): List<String> {
        return properties.allowedModels
    }

    override fun callLlm(request: LlmRequest): LlmResponse {
        val model = request.model
        if (!properties.allowedModels.contains(model)) {
            throw IllegalArgumentException("Requested model is not on the allowed list of free Google AI models: $model")
        }

        val cleanApiKey = properties.apiKey.trim().removeSurrounding("\"").removeSurrounding("'")
        if (cleanApiKey.isBlank()) {
            log.error("Google AI API key is blank/empty!")
            throw IllegalStateException("Google AI API key is not configured.")
        }

        val maskedKey = if (cleanApiKey.length > 12) {
            "${cleanApiKey.take(7)}...${cleanApiKey.takeLast(5)}"
        } else {
            "too-short-or-invalid"
        }
        log.info("Ingested Google AI API key. Length: {}, Masked: {}", cleanApiKey.length, maskedKey)
        log.info("Calling Google AI using model: {}", model)

        val generationConfig = mutableMapOf<String, Any>(
            "responseMimeType" to "application/json"
        )

        if (request.maxTokens != null) {
            generationConfig["maxOutputTokens"] = request.maxTokens
        }
        if (request.temperature != null) {
            generationConfig["temperature"] = request.temperature
        }

        val thinkingBudget = request.extras["thinkingBudget"]?.toString()?.toIntOrNull()
        if (thinkingBudget != null && !model.contains("gemma")) {
            if (model.contains("gemini-3")) {
                val level = when {
                    thinkingBudget == 0 -> "MINIMAL"
                    thinkingBudget <= 1024 -> "LOW"
                    thinkingBudget <= 4096 -> "MEDIUM"
                    else -> "HIGH"
                }
                generationConfig["thinkingConfig"] = mapOf("thinkingLevel" to level)
                log.info("Mapped thinkingBudget $thinkingBudget to thinkingLevel $level for model $model")
            } else {
                generationConfig["thinkingConfig"] = mapOf("thinkingBudget" to thinkingBudget)
                log.info("Using thinkingConfig with thinkingBudget: {} for model {}", thinkingBudget, model)
            }
        }

        val payload = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to request.userPrompt)))
            ),
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to request.systemPrompt))
            ),
            "generationConfig" to generationConfig
        )

        val requestBody = objectMapper.writeValueAsString(payload)

        val url = "${properties.baseUrl.removeSuffix("/")}/v1beta/models/$model:generateContent?key=$cleanApiKey"

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-goog-api-key", cleanApiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .build()

        try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            log.debug("Google AI API Response Status: {}, Body: {}", response.statusCode(), response.body())

            if (response.statusCode() != 200) {
                log.error("Google AI API call failed with status {}: {}", response.statusCode(), response.body())
                throw RuntimeException("Google AI API returned error status ${response.statusCode()}: ${response.body()}")
            }

            val responseNode = objectMapper.readTree(response.body())
            val candidates = responseNode.path("candidates")
            if (candidates.isEmpty) {
                throw RuntimeException("No candidates returned from Gemini API: ${response.body()}")
            }

            val candidate = candidates.get(0)
            val finishReason = candidate.path("finishReason")?.asText()
            if (finishReason == "MAX_TOKENS") {
                log.error("Gemini response was truncated due to token limit.")
                throw RuntimeException("Gemini response was truncated (finishReason=MAX_TOKENS). Try selecting a different model or increasing max output tokens.")
            }

            val contentNode = candidate.path("content")
            val parts = contentNode.path("parts")
            if (parts.isEmpty) {
                throw RuntimeException("No content parts found in candidate")
            }

            val contentText = parts.get(0).path("text")?.asText()
                ?: throw RuntimeException("No text in candidate content part")

            val usageNode = responseNode.path("usageMetadata")
            val promptTokens = usageNode.path("promptTokenCount").asInt(0)
            val completionTokens = usageNode.path("candidatesTokenCount").asInt(0)
            val totalTokens = usageNode.path("totalTokenCount").asInt(0)
            val reasoningTokens = if (usageNode.has("thoughtsTokenCount")) {
                val thoughts = usageNode.path("thoughtsTokenCount").asInt(0)
                if (thoughts > 0) thoughts else null
            } else {
                null
            }

            return LlmResponse(
                content = cleanJsonContent(contentText),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                reasoningTokens = reasoningTokens
            )
        } catch (e: Exception) {
            log.error("Failed to execute Google AI LLM call", e)
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
