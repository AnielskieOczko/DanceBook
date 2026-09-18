package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.ScopeOption
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
    fun deleteThisAndFollowing(occurrenceId: UUID): BulkDeleteResult

    /**
     * Deletes every occurrence of the series. Occurrences with a recorded outcome (attended
     * or skipped) survive as standalone sessions with their Google Calendar events and training
     * records intact; others are removed locally and from Google Calendar.
     */
    fun deleteAll(occurrenceId: UUID): BulkDeleteResult

    /**
     * Calculates the affected session count and recorded outcome count for each of the
     * three delete scopes (THIS_EVENT, THIS_AND_FOLLOWING, ALL_EVENTS).
     */
    fun calculateDeleteScopeOptions(occurrenceId: UUID): List<ScopeOption>
}
