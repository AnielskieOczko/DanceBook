package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class GuidedParseJsonRequest(
    val json: String
)

data class GuidedParseUrlRequest(
    val url: String,
    val model: String,
    val danceTypeId: UUID? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val reasoningEffort: String? = null
)
