package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import java.util.UUID

/** Read-only projection of the signed-in user's training history. */
interface TrainingStatsService {

    fun statsForCurrentUser(period: StatsPeriod, calendarId: UUID? = null): TrainingStats

    companion object {
        /**
         * Consecutive attended sessions counting back from the most recent.
         *
         * Records hold only confirmed outcomes, so this is now exactly what it claims to be: there
         * is no cancelled or unconfirmed session to step over, because neither is history. Only a
         * skip ends the run.
         *
         * Walks the user's whole history rather than the selected period: a streak cut off at a
         * window boundary would report a number that is not the user's streak.
         */
        fun currentStreak(allRecords: List<TrainingRecord>): Int {
            var streak = 0
            for (record in allRecords.sortedByDescending { it.occurredAt }) {
                when (record.outcome) {
                    TrainingOutcome.ATTENDED -> streak++
                    TrainingOutcome.SKIPPED -> return streak
                }
            }
            return streak
        }

        /** Total duration in minutes across attended records in [records]. */
        fun totalAttendedMinutes(records: List<TrainingRecord>): Long =
            records.asSequence()
                .filter { it.outcome == TrainingOutcome.ATTENDED }
                .sumOf { it.durationMinutes.toLong() }

        /** Whole hours trained across attended records in [records]. */
        fun totalAttendedHours(records: List<TrainingRecord>): Int =
            (totalAttendedMinutes(records) / 60L).toInt()
    }
}
