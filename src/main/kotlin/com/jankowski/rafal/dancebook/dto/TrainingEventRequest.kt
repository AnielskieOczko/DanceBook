package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime
import java.util.UUID

/**
 * Backs the training event form. Every field is defaulted so an empty instance can be
 * bound as a @ModelAttribute; enums arrive as Strings and are converted in the service.
 */
data class TrainingEventRequest(
    @field:NotBlank
    val title: String = "",

    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val startTime: LocalDateTime? = null,

    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val endTime: LocalDateTime? = null,

    @field:NotBlank
    val eventType: String = "TRAINING",

    val danceCategoryId: UUID? = null,

    val description: String? = null,

    val materialId: UUID? = null,

    val materialsUrl: String? = null,

    @field:NotBlank
    val attendanceStatus: String = "PLANNED"
)
