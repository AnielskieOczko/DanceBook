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
     * Applies to the Notes and Figures search boxes only; the assistant's read tools keep the
     * repository default. 0.42 comes from #331: with `gemini-embedding-001` a query that has real
     * answers has its nearest match at 0.23 to 0.37, while a query with none starts at 0.43 or more,
     * so the old 0.65 never dropped anything (the 30th candidate was never past 0.53).
     */
    val semanticSearchMaxDistance: Double = 0.42,
    /** Share of the per-minute embedding budget live searches may use; the index worker keeps the rest. */
    val embeddingSearchSharePercent: Int = 30,
    /** Searches that may embed per user per minute, so one user cannot use up the search share. */
    val embeddingSearchPerUserPerMinute: Int = 5,
    /** Share of the per-minute embedding budget for the assistant's own query embeddings, separate from the search box. */
    val embeddingInteractiveSharePercent: Int = 20,
    /** Assistant embeddings per user per minute (one question can use several). */
    val embeddingInteractivePerUserPerMinute: Int = 15,
    /** How long the interactive assistant waits for an embedding slot before giving up, in ms. */
    val embeddingInteractiveMaxWaitMs: Long = 2000
)
