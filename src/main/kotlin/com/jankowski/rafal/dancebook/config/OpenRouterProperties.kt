package com.jankowski.rafal.dancebook.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "openrouter")
data class OpenRouterProperties(
    val apiKey: String = "",
    val defaultModel: String = "nvidia/nemotron-3-nano-30b-a3b:free",
    val allowedFreeModels: List<String> = listOf(
        "nvidia/nemotron-3-nano-30b-a3b:free",
        "google/gemini-2.5-flash:free",
        "meta-llama/llama-3-8b-instruct:free",
        "mistralai/mistral-7b-instruct:free",
        "qwen/qwen-2-7b-instruct:free"
    ),
    val timeoutSeconds: Long = 120
)
