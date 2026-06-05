package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class GuidedParseJsonRequest(
    val json: String
)

data class GuidedParseUrlRequest(
    val url: String,
    val model: String,
    val danceTypeId: UUID? = null
)
