package com.jankowski.rafal.dancebook.dto

import java.time.LocalDate
import java.util.UUID

/** Everything the home page shows, computed once per request by `DashboardService`. */
data class DashboardView(
    val greeting: String,
    val dateLabel: String,
    val week: WeekStrip,
    val wrapUp: WrapUp,
    val recentNotes: List<RecentNote>,
    val month: MonthSummary,
    val continueItems: List<ContinueItem>
)

/**
 * How a day of the week strip is drawn.
 *
 * Declared in precedence order, highest first: a day holding both a session to confirm and a
 * planned one reads as needing confirmation, the same precedence `TrainingEventPalette` applies
 * to a single session. Skipped and cancelled sessions leave the day looking empty.
 */
enum class WeekDayState { UNCONFIRMED, ATTENDED, PLANNED, NONE }

data class WeekDay(
    val date: LocalDate,
    val weekdayLabel: String,
    val dayOfMonth: Int,
    /** Named `today`, not `isToday`: SpEL reads `day.today` from `getToday()`, not `isIsToday()`. */
    val today: Boolean,
    val state: WeekDayState
)

data class WeekStrip(
    val days: List<WeekDay>,
    /** Consecutive attended sessions counting back from the latest; zero hides the line. */
    val streak: Int,
    val toConfirmCount: Int
)

/** A category chip on a session card: "Waltz · 45m". */
data class SessionSegment(val label: String, val durationLabel: String)

data class SessionCard(
    val id: UUID,
    val title: String,
    val dateLabel: String,
    val timeLabel: String,
    val segments: List<SessionSegment>,
    /** The note already linked to the session, if any; its presence turns "Write" into "Open". */
    val noteId: UUID?
)

/**
 * The hero slot. [waiting] holds sessions still to confirm, newest first: the first is the
 * hero and the rest are compact rows. Only when nothing is waiting does [next] hold the next
 * upcoming session, and when that is missing too the slot is an empty state.
 */
data class WrapUp(
    val waiting: List<SessionCard>,
    val next: SessionCard?
) {
    val hero: SessionCard? get() = waiting.firstOrNull()
    val older: List<SessionCard> get() = waiting.drop(1)
}

/** A figure pinned to a note, with the step timing of its default step set ("S Q Q"). */
data class PinnedFigure(val name: String, val timing: String?)

data class RecentNote(
    val id: UUID,
    val title: String,
    val danceLabel: String?,
    val whenLabel: String,
    val excerpt: String,
    val figures: List<PinnedFigure>
) {
    val metaLabel: String get() = listOfNotNull(danceLabel, whenLabel).joinToString(" · ")
}

data class MonthSummary(
    val trainedLabel: String,
    val attended: Int,
    /** Null when nothing has been decided yet, which is not the same as 0%. */
    val attendanceRatePercent: Int?,
    val byCategory: List<BreakdownSlice>
)

enum class ContinueKind(val label: String) { CHOREOGRAPHY("Choreography"), COLLECTION("Collection") }

data class ContinueItem(
    val kind: ContinueKind,
    val id: UUID,
    val name: String,
    val href: String,
    val detail: String
)
