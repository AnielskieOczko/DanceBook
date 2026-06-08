package com.jankowski.rafal.dancebook.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ollama")
data class OllamaProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://ollama.com",
    val allowedModels: List<String> = listOf(
        "minimax-m3:cloud",
        "qwen3.5:397b-cloud",
        "glm-5.1:cloud",
        "gemma4:31b-cloud"
    ),
    val timeoutSeconds: Long = 300
)
