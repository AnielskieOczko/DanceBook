package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome

/**
 * The one place a training session's status becomes a colour.
 *
 * Shared by the calendar feed and the legend the calendar page draws.
 *
 * Values are design tokens resolved from the stylesheet at runtime, not hardcoded hex strings.
 */
object TrainingEventPalette {

    /** A status as the calendar draws it: solid colour for the stripe, tinted fill behind it. */
    data class Swatch(
        val key: String,
        val label: String,
        val color: String
    ) {
        /** The same colour at ~10% alpha, derived as a CSS color-mix. */
        val tint: String get() = "color-mix(in srgb, $color 10%, transparent)"
    }

    private const val COLOR_PLANNED = "var(--color-on-surface)"
    private const val COLOR_ATTENDED = "var(--color-primary)"
    private const val COLOR_SKIPPED = "var(--color-error)"
    private const val COLOR_CANCELLED = "var(--color-outline)"
    private const val COLOR_UNCONFIRMED = "var(--color-warning)"

    /** Text sits on the tint, not on the solid colour, so it stays on-surface throughout. */
    const val TEXT_COLOR = "var(--color-on-surface)"

    val PLANNED = Swatch("planned", "Planned", COLOR_PLANNED)
    val UNCONFIRMED = Swatch("unconfirmed", "Needs confirmation", COLOR_UNCONFIRMED)
    val ATTENDED = Swatch("attended", "Attended", COLOR_ATTENDED)
    val SKIPPED = Swatch("skipped", "Skipped", COLOR_SKIPPED)
    val CANCELLED = Swatch("cancelled", "Cancelled", COLOR_CANCELLED)

    /** Legend order, read left to right as a session's likely life: planned first, gone last. */
    val LEGEND: List<Swatch> = listOf(PLANNED, UNCONFIRMED, ATTENDED, SKIPPED, CANCELLED)

    /**
     * Slice colours for the statistics charts, cycled by the slice's sorted position so a
     * dance style keeps one colour across renders and across both charts.
     *
     * Defined once as chart tokens in the stylesheet and drawn from CSS custom properties at runtime.
     */
    private val CHART_COLORS = listOf(
        "var(--color-chart-1)",
        "var(--color-chart-2)",
        "var(--color-chart-3)",
        "var(--color-chart-4)",
        "var(--color-chart-5)",
        "var(--color-chart-6)",
        "var(--color-chart-7)",
        "var(--color-chart-8)"
    )

    /** Time inside an attended session that no segment claimed: present, but not a style. */
    const val UNASSIGNED_COLOR = "var(--color-outline-variant)"

    /** Axis grid lines on the bar chart in the statistics dashboard. */
    const val CHART_GRID_COLOR = "var(--color-outline-variant)"

    fun chartColor(index: Int): String = CHART_COLORS[Math.floorMod(index, CHART_COLORS.size)]

    fun swatchFor(event: TrainingEvent, user: AppUser): Swatch {
        val status = event.attendanceFor(user)
        return when {
            event.isAwaitingConfirmationFor(user) -> UNCONFIRMED
            status == AttendanceStatus.ATTENDED -> ATTENDED
            status == AttendanceStatus.SKIPPED -> SKIPPED
            status == AttendanceStatus.CANCELLED -> CANCELLED
            else -> PLANNED
        }
    }

    /**
     * The colour of a recorded outcome.
     *
     * An orphaned record has no session left to ask, so it cannot go through the event
     * overload — but it must still come out the same colour the session had.
     */
    fun swatchFor(outcome: TrainingOutcome): Swatch = when (outcome) {
        TrainingOutcome.ATTENDED -> ATTENDED
        TrainingOutcome.SKIPPED -> SKIPPED
    }
}
