package com.jankowski.rafal.dancebook.dto

data class GuidedParseResult(
    val success: Boolean,
    val request: DanceFigureRequest? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val usage: LlmUsageStats? = null
)

data class LlmUsageStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val reasoningTokens: Int? = null
)
