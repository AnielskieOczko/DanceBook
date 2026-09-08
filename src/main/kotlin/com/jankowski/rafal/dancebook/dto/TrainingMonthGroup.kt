package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.TrainingEvent
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/**
 * A session as a training page draws it. The swatch travels with the event so the template never
 * has to re-derive a colour from a status, which is how the palette came to be duplicated.
 */
data class TrainingEventRow(
    val event: TrainingEvent,
    val swatch: TrainingEventPalette.Swatch
)

/**
 * One month of training, with the totals that make a training log worth grouping.
 */
data class TrainingMonthGroup(
    val label: String,
    val rows: List<TrainingEventRow>,
    val totalMinutes: Long
) {
    val sessionCount: Int get() = rows.size

    val sessionLabel: String get() = if (sessionCount == 1) "1 session" else "$sessionCount sessions"

    /** "9h 30m", "45m", "3h" -- whichever parts are non-zero. */
    val totalLabel: String get() = formatMinutes(totalMinutes)
}

/**
 * Groups sessions into months, preserving the order they arrive in: the agenda and the timeline
 * both hand this a sorted list and expect the months back in that same direction.
 *
 * Lives here rather than in a controller because two pages now draw the same grouping, and a
 * second copy is what would let their month headings or colours drift apart.
 */
fun groupByMonth(events: List<TrainingEvent>): List<TrainingMonthGroup> =
    events.groupBy { YearMonth.from(it.startTime) }
        .map { (month, monthEvents) ->
            TrainingMonthGroup(
                label = month.format(MONTH_LABEL),
                rows = monthEvents.map { TrainingEventRow(it, TrainingEventPalette.swatchFor(it)) },
                totalMinutes = monthEvents.sumOf { it.durationMinutes }
            )
        }
