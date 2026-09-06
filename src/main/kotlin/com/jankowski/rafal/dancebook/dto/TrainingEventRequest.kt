package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Backs the training event form.
 *
 * Time is a date plus two times rather than two datetime-local inputs, matching how Google
 * Calendar's editor works; [endDate] covers the multi-day case (camps, competitions) and
 * defaults to [date]. Every field is defaulted so an empty instance can be bound as a
 * @ModelAttribute, and enums arrive as Strings and are converted in the service.
 */
data class TrainingEventRequest(
    @field:NotBlank
    val title: String = "",

    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val date: LocalDate? = null,

    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    val startTime: LocalTime? = null,

    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    val endTime: LocalTime? = null,

    /** Null means the session ends on the day it started. */
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val endDate: LocalDate? = null,

    @field:NotBlank
    val eventType: String = "TRAINING",

    /** Style breakdown; empty means the session is not style-specific. */
    val segments: MutableList<TrainingEventSegmentRequest> = mutableListOf(),

    val description: String? = null,

    val materialId: UUID? = null,

    val materialsUrl: String? = null,

    @field:NotBlank
    val attendanceStatus: String = "PLANNED"
) {
    /** The effective end date, applying the same-day default. */
    fun effectiveEndDate(): LocalDate? = endDate ?: date
}
