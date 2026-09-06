package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.TrainingEvent
import java.util.UUID

interface TrainingSeriesService {

    /** Creates the series and every occurrence it generates; returns the first one. */
    fun create(request: TrainingEventRequest): TrainingEvent

    /**
     * Applies an edit to the given occurrence and every later one, leaving completed
     * sessions untouched. Returns the regenerated occurrence at the original date.
     */
    fun updateThisAndFollowing(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent

    /** Deletes the given occurrence and every later one, leaving completed sessions alone. */
    fun deleteThisAndFollowing(occurrenceId: UUID)
}
