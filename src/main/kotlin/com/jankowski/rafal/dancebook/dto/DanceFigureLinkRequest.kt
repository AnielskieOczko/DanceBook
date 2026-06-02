package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class DanceFigureLinkRequest(
    val id: UUID? = null,
    val url: String = "",
    val title: String? = null,
    val type: String? = null // "video", "syllabus", "other"
)
