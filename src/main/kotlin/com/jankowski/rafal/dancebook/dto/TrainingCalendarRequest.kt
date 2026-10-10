package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.Visibility
import jakarta.validation.constraints.NotBlank

data class TrainingCalendarRequest(
    @field:NotBlank(message = "{validation.training_calendar.google_id_empty}")
    val googleCalendarId: String = "",

    @field:NotBlank(message = "{validation.training_calendar.display_name_empty}")
    val displayName: String = "",

    val visibility: Visibility = Visibility.PRIVATE,

    val color: String? = null
)
