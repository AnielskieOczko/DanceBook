package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.PatternReconcilePlan
import com.jankowski.rafal.dancebook.dto.ScopeOption
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.TrainingEvent
import java.util.UUID

interface TrainingSeriesService {

    /** Creates the series and every occurrence it generates; returns the first one. */
    fun create(request: TrainingEventRequest): TrainingEvent

    /**
     * Applies an edit to a single occurrence of a series, detaching it from the series
     * so subsequent series-wide edits no longer touch it. If it was the last occurrence
     * in the series, the series definition is deleted.
     */
    fun updateThisEvent(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent

    /**
     * Applies a content-only edit to the given occurrence and every later one in place,
     * keeping occurrence IDs, Google Calendar event IDs, dates, times, attendance statuses and
     * recorded outcomes intact.
     */
    fun updateThisAndFollowing(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent

    /**
     * Applies an edit to every occurrence of the series (past and future). If the recurrence
     * pattern (weekday, start/end times, repeat-until) is changed, reconciles occurrences:
     * moving surviving occurrences to new dates/times in place (keeping rows, Google events,
     * and attendance), generating missing occurrences, and trimming excess occurrences
     * (leaving recorded ones as standalone sessions).
     */
    fun updateAll(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent

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

    /**
     * Calculates the created, moved, and removed counts, and the number of past sessions
     * with recorded outcomes that would be dropped from the series by a pattern change.
     */
    fun calculatePatternReconcile(occurrenceId: UUID, request: TrainingEventRequest): PatternReconcilePlan
}
