package com.jankowski.rafal.dancebook.service

interface LlmProvider {
    val providerName: String // "openrouter" | "google-ai" | "ollama"
    fun getModels(): List<String>
    fun callLlm(request: LlmRequest): LlmResponse
}

data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val model: String,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val extras: Map<String, Any?> = emptyMap()
)

data class LlmResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val reasoningTokens: Int?
)
