package com.jankowski.rafal.dancebook.dto

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A window over training history, chosen on the dashboard.
 *
 * Windows are left-bounded only: "last 30 days" means everything from 30 days ago
 * *onwards*, future sessions included, so the upcoming-sessions figure does not read zero
 * on every period but [ALL_TIME].
 */
enum class StatsPeriod(val label: String) {
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year"),
    ALL_TIME("All time");

    /** The earliest day this period admits, or null when it admits everything. */
    fun earliestDay(today: LocalDate): LocalDate? = when (this) {
        LAST_30_DAYS -> today.minusDays(30)
        THIS_YEAR -> today.withDayOfYear(1)
        ALL_TIME -> null
    }

    /**
     * Membership is decided by the session's start, not its end.
     *
     * [today] has no default deliberately: a caller reducing many events must supply one
     * `LocalDate.now()` up front and pass it to every call, or a render straddling midnight
     * could judge some events against one day and the rest against the next.
     */
    fun contains(startTime: LocalDateTime, today: LocalDate): Boolean {
        val earliest = earliestDay(today) ?: return true
        return !startTime.toLocalDate().isBefore(earliest)
    }
}

/**
 * Five disjoint buckets: every session in the period lands in exactly one.
 *
 * [unconfirmed] takes precedence over the raw status, matching how
 * `TrainingEventPalette.swatchFor` decides a session's colour — a past session still
 * marked planned is "needs confirming" everywhere in the app, not "planned".
 */
data class SessionCounts(
    val upcoming: Int,
    val unconfirmed: Int,
    val attended: Int,
    val skipped: Int,
    val cancelled: Int
) {
    val total: Int get() = upcoming + unconfirmed + attended + skipped + cancelled
}

/** One slice of a breakdown chart, carrying its own colour and pre-formatted duration. */
data class BreakdownSlice(
    val label: String,
    val minutes: Long,
    val sessionCount: Int,
    val color: String
) {
    /** Serialised alongside the rest, so chart tooltips need no formatter of their own. */
    val durationLabel: String get() = formatMinutes(minutes)
}

data class TrainingStats(
    val period: StatsPeriod,
    val totalMinutesTrained: Long,
    val counts: SessionCounts,
    /** Null when nothing has been decided yet — a different state from "nothing attended". */
    val attendanceRatePercent: Int?,
    /** Always over full history, never the selected period. A truncated streak is a lie. */
    val currentStreak: Int,
    val byCategory: List<BreakdownSlice>,
    val byEventType: List<BreakdownSlice>
) {
    val totalTrainedLabel: String get() = formatMinutes(totalMinutesTrained)
}

/** `195` becomes `3h 15m`, `120` becomes `2h`, `45` becomes `45m`. */
fun formatMinutes(minutes: Long): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "${remainder}m"
        remainder == 0L -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}
