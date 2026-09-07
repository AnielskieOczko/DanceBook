package com.jankowski.rafal.dancebook.controller

import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent

/**
 * The one place a training session's status becomes a colour.
 *
 * Shared by the calendar feed and the legend the calendar page draws, which were previously
 * two copies of the same five hex values that had to be kept in sync by hand.
 *
 * Values are Noble Harmony tokens, resolved here because neither JS nor a script-applied
 * class name can reach Tailwind's palette at runtime.
 */
object TrainingEventPalette {

    /** A status as the calendar draws it: solid colour for the stripe, tinted fill behind it. */
    data class Swatch(
        val key: String,
        val label: String,
        val color: String
    ) {
        /** The same colour at ~10% alpha, as an 8-digit hex the browser accepts directly. */
        val tint: String get() = "$color$TINT_ALPHA"
    }

    /** ~10% opacity. Enough to read as a category, faint enough to keep text at full contrast. */
    private const val TINT_ALPHA = "1a"

    private const val COLOR_PLANNED = "#1e2524"      // primary-container
    private const val COLOR_ATTENDED = "#2e5d51"     // success
    private const val COLOR_SKIPPED = "#ba1a1a"      // danger
    private const val COLOR_CANCELLED = "#737877"    // outline
    private const val COLOR_UNCONFIRMED = "#695d46"  // secondary — wants attention

    /** Text sits on the tint, not on the solid colour, so it stays on-surface throughout. */
    const val TEXT_COLOR = "#1b1c1b"

    val PLANNED = Swatch("planned", "Planned", COLOR_PLANNED)
    val UNCONFIRMED = Swatch("unconfirmed", "Needs confirmation", COLOR_UNCONFIRMED)
    val ATTENDED = Swatch("attended", "Attended", COLOR_ATTENDED)
    val SKIPPED = Swatch("skipped", "Skipped", COLOR_SKIPPED)
    val CANCELLED = Swatch("cancelled", "Cancelled", COLOR_CANCELLED)

    /** Legend order, read left to right as a session's likely life: planned first, gone last. */
    val LEGEND: List<Swatch> = listOf(PLANNED, UNCONFIRMED, ATTENDED, SKIPPED, CANCELLED)

    fun swatchFor(event: TrainingEvent): Swatch = when {
        event.isAwaitingConfirmation -> UNCONFIRMED
        event.attendanceStatus == AttendanceStatus.ATTENDED -> ATTENDED
        event.attendanceStatus == AttendanceStatus.SKIPPED -> SKIPPED
        event.attendanceStatus == AttendanceStatus.CANCELLED -> CANCELLED
        else -> PLANNED
    }
}
