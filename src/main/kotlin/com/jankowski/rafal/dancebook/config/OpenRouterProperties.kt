package com.jankowski.rafal.dancebook.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "openrouter")
data class OpenRouterProperties(
    val apiKey: String = "",
    val defaultModel: String = "nvidia/nemotron-3-nano-30b-a3b:free",
    val allowedFreeModels: List<String> = listOf(
        "nvidia/nemotron-3-nano-30b-a3b:free",
        "nvidia/nemotron-3.5-content-safety:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "google/gemma-4-31b-it:free",
        "google/gemma-4-26b-a4b-it:free",
        "moonshotai/kimi-k2.6:free",
        "z-ai/glm-4.5-air:free"
    ),
    val timeoutSeconds: Long = 120
)
