package com.jankowski.rafal.dancebook.dto

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One outcome dot in the weekly rollup dots bar.
 */
data class TrainingTimelineDot(
    val title: String,
    val statusClass: String // "a" (attended), "s" (skipped), "u" (unconfirmed), "f" (future/planned)
)

/**
 * One style rollup in the week summary.
 */
data class TrainingTimelineStyleRollup(
    val name: String,
    val minutes: Long
) {
    val label: String get() = "$name ${minutes}m"
}

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
    val startsTodayBoundary: Boolean,
    val activeStreak: Int? = null,
    val streakReachedDate: String? = null,
    val todayFormatted: String? = null,
    val streakMilestone: Int? = activeStreak,
    val streakTier: String? = null
) {
    val event get() = row.event
    val swatch get() = row.swatch
    val isAttended: Boolean get() = swatch.key == "attended"
    val isSkipped: Boolean get() = swatch.key == "skipped"
    val isUnconfirmed: Boolean get() = swatch.key == "unconfirmed"
    val isCancelled: Boolean get() = swatch.key == "cancelled"
    val untrackedMinutes: Long get() = (event.durationMinutes - event.segments.sumOf { it.durationMinutes.toLong() }).coerceAtLeast(0)
}

/**
 * One week in the journey feed with its rollup metrics.
 */
data class TrainingTimelineWeek(
    val weekNumber: Int,
    val isCurrentWeek: Boolean,
    val dateRangeLabel: String,
    val entries: List<TrainingTimelineEntry>,
    val totalMinutes: Long,
    val attendedCount: Int,
    val totalDecidedCount: Int,
    val styles: List<TrainingTimelineStyleRollup> = emptyList(),
    val dots: List<TrainingTimelineDot> = emptyList(),
    val weekLabel: String = "",
    val totalWeekSessions: Int = entries.size
) {
    val sessionCount: Int get() = totalWeekSessions
    val totalLabel: String get() = formatMinutes(totalMinutes)
    val attendanceRatioLabel: String
        get() = "$attendedCount/${if (totalDecidedCount > 0) totalDecidedCount else sessionCount}"
    val stylesLabel: String
        get() = styles.joinToString(" · ") { it.label }
}

/**
 * Builds weekly rollups from a list of timeline entries.
 */
fun buildWeeksFromEntries(
    entries: List<TrainingTimelineEntry>,
    today: LocalDate = LocalDate.now(),
    locale: Locale = Locale.ENGLISH
): List<TrainingTimelineWeek> {
    if (entries.isEmpty()) return emptyList()
    val byMonday = LinkedHashMap<LocalDate, MutableList<TrainingTimelineEntry>>()
    for (entry in entries) {
        val monday = entry.event.startTime.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        byMonday.computeIfAbsent(monday) { mutableListOf() }.add(entry)
    }
    return byMonday.map { (monday, weekEntries) ->
        val sunday = monday.plusDays(6)
        val weekNumber = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val isCurrentWeek = !today.isBefore(monday) && !today.isAfter(sunday)
        val datePattern = if (locale.language == "pl") "d MMM" else "MMM d"
        val formatter = DateTimeFormatter.ofPattern(datePattern, locale)
        val dateRangeLabel = "${monday.format(formatter)} – ${sunday.format(formatter)}"

        val attendedEntries = weekEntries.filter { it.isAttended }
        val totalMinutesTrained = attendedEntries.sumOf { it.event.durationMinutes }
        val attendedCount = attendedEntries.size
        val skippedCount = weekEntries.count { it.isSkipped }
        val unconfirmedCount = weekEntries.count { it.isUnconfirmed }
        val totalDecidedCount = attendedCount + skippedCount + unconfirmedCount

        val styleMins = LinkedHashMap<String, Long>()
        for (entry in attendedEntries) {
            for (seg in entry.event.segments) {
                val name = seg.danceCategory?.name ?: ""
                if (name.isNotBlank()) {
                    styleMins[name] = (styleMins[name] ?: 0L) + seg.durationMinutes
                }
            }
        }
        val styles = styleMins.map { (name, mins) -> TrainingTimelineStyleRollup(name, mins) }

        val dots = weekEntries.sortedBy { it.event.startTime }.map { entry ->
            val statusClass = when {
                entry.isUnconfirmed -> "u"
                entry.isAttended -> "a"
                entry.isSkipped -> "s"
                entry.isCancelled -> "s"
                else -> "f"
            }
            TrainingTimelineDot(title = entry.event.title, statusClass = statusClass)
        }

        TrainingTimelineWeek(
            weekNumber = weekNumber,
            isCurrentWeek = isCurrentWeek,
            dateRangeLabel = dateRangeLabel,
            entries = weekEntries,
            totalMinutes = totalMinutesTrained,
            attendedCount = attendedCount,
            totalDecidedCount = totalDecidedCount,
            styles = styles,
            dots = dots
        )
    }
}

/** One month of the timeline, carrying the same totals the agenda shows. */
data class TrainingTimelineMonth(
    val label: String,
    val entries: List<TrainingTimelineEntry>,
    val totalMinutes: Long,
    val weeks: List<TrainingTimelineWeek> = buildWeeksFromEntries(entries)
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
    val isFirstPage: Boolean,
    val stats: TrainingStats? = null
) {
    val isEmpty: Boolean get() = months.isEmpty() || months.all { it.entries.isEmpty() && it.weeks.isEmpty() }
}
