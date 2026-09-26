package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.Visibility
import jakarta.validation.constraints.NotBlank

data class TrainingCalendarRequest(
    @field:NotBlank(message = "Google Calendar ID cannot be empty")
    val googleCalendarId: String = "",

    @field:NotBlank(message = "Display name cannot be empty")
    val displayName: String = "",

    val visibility: Visibility = Visibility.PRIVATE,

    val color: String? = null
)
