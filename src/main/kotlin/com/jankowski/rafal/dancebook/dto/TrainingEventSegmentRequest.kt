package com.jankowski.rafal.dancebook.dto

import java.util.UUID

/**
 * One style row on the training event form. Both fields are nullable so an empty row the
 * user added but never filled in can be skipped rather than rejected.
 */
data class TrainingEventSegmentRequest(
    val categoryId: UUID? = null,
    val durationMinutes: Int? = null
)
