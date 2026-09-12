package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.TrainingRecord

/**
 * One recorded session as the history page draws it.
 *
 * The swatch travels with the record for the same reason [TrainingEventRow] carries one: so
 * no template re-derives a colour from a status, which is how the palette came to be
 * duplicated in the first place.
 */
data class TrainingHistoryRow(
    val record: TrainingRecord,
    val swatch: TrainingEventPalette.Swatch
) {
    /** Its calendar session is gone. The page says so, and offers to remove the record. */
    val isOrphaned: Boolean get() = record.isOrphaned
}

/**
 * One month of confirmed training.
 *
 * [attendedMinutes] counts only the sessions actually attended — a month's total is hours
 * trained, and a skipped session contributes none — while [sessionCount] counts every
 * confirmed session, attended and skipped alike, because that is how many rows are shown.
 */
data class TrainingHistoryMonth(
    val label: String,
    val rows: List<TrainingHistoryRow>,
    val attendedMinutes: Long
) {
    val sessionCount: Int get() = rows.size

    val sessionLabel: String get() = if (sessionCount == 1) "1 session" else "$sessionCount sessions"

    val totalLabel: String get() = formatMinutes(attendedMinutes)
}

/** The whole of a user's confirmed training, newest month first. */
data class TrainingHistory(
    val months: List<TrainingHistoryMonth>
) {
    val isEmpty: Boolean get() = months.isEmpty()

    /** Drives the page's explanation of what an orphaned row is; zero hides it. */
    val orphanedCount: Int get() = months.sumOf { month -> month.rows.count { it.isOrphaned } }
}
