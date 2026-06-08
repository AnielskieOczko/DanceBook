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

    @JvmOverloads
    fun callLlm(
        systemPrompt: String,
        userPrompt: String,
        requestedModel: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        reasoningEffort: String? = null
    ): OpenRouterResponse {
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

        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "response_format" to mapOf("type" to "json_object"),
            "max_tokens" to (maxTokens ?: 16384),
            "temperature" to (temperature ?: 1.0)
        )

        if (reasoningEffort != null && reasoningEffort != "default") {
            if (reasoningEffort == "none") {
                payload["reasoning"] = mapOf(
                    "effort" to "none",
                    "exclude" to true
                )
            } else {
                payload["reasoning"] = mapOf(
                    "effort" to reasoningEffort
                )
            }
        }

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
            log.info("OpenRouter API Response Status: {}, Body: {}", response.statusCode(), response.body())
            
            if (response.statusCode() != 200) {
                log.error("OpenRouter API call failed with status {}: {}", response.statusCode(), response.body())
                throw RuntimeException("OpenRouter API returned error status ${response.statusCode()}: ${response.body()}")
            }

            val responseNode = objectMapper.readTree(response.body())
            val choiceNode = responseNode.path("choices").get(0)
                ?: throw RuntimeException("No choices in LLM response")
            val finishReason = choiceNode.path("finish_reason")?.asText()
            if (finishReason == "length") {
                log.error("LLM response was truncated (finish_reason=length). The output exceeded max_tokens.")
                throw RuntimeException("The AI model's response was truncated because it exceeded the maximum token limit (max_tokens). This usually happens when the dance figure text is extremely long, or the model spent too many tokens on 'thinking/reasoning'. Try selecting a different model, or if using a reasoning model, disabling/reducing its thinking budget.")
            }
            val messageNode = choiceNode.path("message")
            val content = messageNode?.path("content")?.asText()
                ?: throw RuntimeException("Could not extract content from LLM response choice")

            val usageNode = responseNode.path("usage")
            val promptTokens = usageNode.path("prompt_tokens").asInt(0)
            val completionTokens = usageNode.path("completion_tokens").asInt(0)
            val totalTokens = usageNode.path("total_tokens").asInt(0)
            val reasoningTokens = if (usageNode.has("completion_tokens_details")) {
                val reasoning = usageNode.path("completion_tokens_details").path("reasoning_tokens").asInt(0)
                if (reasoning > 0) reasoning else null
            } else {
                null
            }

            return OpenRouterResponse(
                content = cleanJsonContent(content),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                reasoningTokens = reasoningTokens
            )
        } catch (e: Exception) {
            log.error("Failed to execute OpenRouter LLM call", e)
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

data class OpenRouterResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val reasoningTokens: Int?
)
