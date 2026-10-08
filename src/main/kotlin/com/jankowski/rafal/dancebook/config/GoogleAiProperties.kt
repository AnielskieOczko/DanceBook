package com.jankowski.rafal.dancebook.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "google.ai")
data class GoogleAiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val allowedModels: List<String> = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3-flash",
        "gemma-4-26b-a4b-it",
        "gemma-4-31b-it"
    ),
    val timeoutSeconds: Long = 120,
    /** The chat model behind the assistant (Spring AI). Independent of the LlmProvider models. */
    val assistantModel: String = "gemini-2.5-flash",
    /** Total time one assistant turn may spend waiting on the provider. */
    val assistantTimeoutSeconds: Long = 30,
    /** The embedding model behind knowledge retrieval (Spring AI). */
    val embeddingModel: String = "gemini-embedding-001",
    /** Vector dimension for embeddings. */
    val embeddingDimensions: Int = 768,
    /** Maximum embedding requests per minute. Defaults to 90 (below free tier limit of 100). */
    val embeddingRequestsPerMinute: Int = 90,
    /**
     * Cosine distance (0 identical, 1 unrelated) beyond which a semantic search match is dropped.
     * 0.65 is the cutoff the knowledge index's hybrid query has always used.
     */
    val semanticSearchMaxDistance: Double = 0.65,
    /** Share of the per-minute embedding budget live searches may use; the index worker keeps the rest. */
    val embeddingSearchSharePercent: Int = 30,
    /** Searches that may embed per user per minute, so one user cannot use up the search share. */
    val embeddingSearchPerUserPerMinute: Int = 5
)
