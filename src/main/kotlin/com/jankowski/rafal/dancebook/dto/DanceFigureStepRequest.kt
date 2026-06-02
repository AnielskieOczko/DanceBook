package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class DanceFigureStepRequest(
    val id: UUID? = null,
    val stepNumber: Int = 1,
    val timing: String = "",
    val role: String = "LEADER", // "LEADER" or "FOLLOWER"
    val foot: String = "",       // "LF", "RF", "TOGETHER", etc.
    val action: String = "",
    val footwork: String? = null,
    val alignment: String? = null,
    val amountOfTurn: String? = null,
    val commentsText: String? = null // Newline-separated comments
)
