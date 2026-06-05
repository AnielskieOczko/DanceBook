package com.jankowski.rafal.dancebook.dto

data class GuidedParseResult(
    val success: Boolean,
    val request: DanceFigureRequest? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
