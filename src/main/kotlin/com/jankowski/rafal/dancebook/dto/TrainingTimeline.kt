package com.jankowski.rafal.dancebook.dto

/**
 * One session on the timeline.
 *
 * [isFuture] separates what is still coming from what has already happened, which the page
 * draws differently; [startsTodayBoundary] marks the single entry the "Today" divider sits
 * above, so the template never has to compare dates itself.
 */
data class TrainingTimelineEntry(
    val row: TrainingEventRow,
    val isFuture: Boolean,
    val startsTodayBoundary: Boolean
) {
    val event get() = row.event
    val swatch get() = row.swatch
}

/** One month of the timeline, carrying the same totals the agenda shows. */
data class TrainingTimelineMonth(
    val label: String,
    val entries: List<TrainingTimelineEntry>,
    val totalMinutes: Long
) {
    val sessionCount: Int get() = entries.size

    val sessionLabel: String get() = if (sessionCount == 1) "1 session" else "$sessionCount sessions"

    val totalLabel: String get() = formatMinutes(totalMinutes)
}

/**
 * One window of the timeline: newest first, oldest last.
 *
 * [lastMonthLabel] is echoed back on the next request so an appended window can tell whether
 * its first month is a new one or a continuation of the month already on screen, and suppress
 * a duplicate heading. [nextPage] is meaningless when [hasMore] is false.
 */
data class TrainingTimeline(
    val months: List<TrainingTimelineMonth>,
    val hasMore: Boolean,
    val nextPage: Int,
    val lastMonthLabel: String?,
    /** True only for the very first window, which is the one that owns the empty state. */
    val isFirstPage: Boolean
) {
    val isEmpty: Boolean get() = months.isEmpty()
}
