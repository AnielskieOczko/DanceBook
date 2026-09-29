package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.ContinueItem
import com.jankowski.rafal.dancebook.dto.ContinueKind
import com.jankowski.rafal.dancebook.dto.DashboardView
import com.jankowski.rafal.dancebook.dto.MonthSummary
import com.jankowski.rafal.dancebook.dto.PinnedFigure
import com.jankowski.rafal.dancebook.dto.RecentNote
import com.jankowski.rafal.dancebook.dto.SessionCard
import com.jankowski.rafal.dancebook.dto.SessionSegment
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.WeekDay
import com.jankowski.rafal.dancebook.dto.WeekDayState
import com.jankowski.rafal.dancebook.dto.WeekStrip
import com.jankowski.rafal.dancebook.dto.WrapUp
import com.jankowski.rafal.dancebook.dto.formatMinutes
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.EntryType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import com.jankowski.rafal.dancebook.repository.CustomListRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.jsoup.Jsoup
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

/**
 * The home page, organised around wrapping up a session (#147).
 *
 * Reads through the existing services so it inherits their scoping: sessions follow the active
 * calendar exactly as the stats page does, and notes, choreographies and collections are the
 * current user's own. Every "now" comes from the injected [Clock], so a page rendered across
 * midnight judges everything against one day and tests can pin the time.
 */
@Service
@Transactional(readOnly = true)
class DashboardServiceImpl(
    private val trainingEventService: TrainingEventService,
    private val trainingStatsService: TrainingStatsService,
    private val activeCalendarService: ActiveCalendarService,
    private val appUserService: AppUserService,
    private val materialRepository: MaterialRepository,
    private val choreographyRepository: ChoreographyRepository,
    private val customListRepository: CustomListRepository,
    private val clock: Clock
) : DashboardService {

    companion object {
        private const val RECENT_NOTES = 3
        private const val CONTINUE_ITEMS = 3
        private const val EXCERPT_LENGTH = 140

        /** How far ahead "next session" looks; a session beyond this is not a next one. */
        private const val NEXT_SESSION_HORIZON_DAYS = 365L

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)
        private val LONG_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        private val SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        private val FULL_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        /** Morning is 05:00-11:59 and afternoon 12:00-17:59; everything else is evening. */
        fun greetingFor(hour: Int): String = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    override fun dashboardForCurrentUser(): DashboardView {
        val user = appUserService.getCurrentUser()
        val calendarId = activeCalendarService.active()?.id
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()

        val waiting = awaitingConfirmation(user, calendarId, now)
        val wrapUp = WrapUp(
            waiting = waiting.map { toCard(it) },
            next = if (waiting.isEmpty()) nextSession(user, calendarId, now)?.let { toCard(it) } else null
        )
        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.THIS_MONTH, calendarId)
        val name = user.displayName.ifBlank { user.username }

        return DashboardView(
            greeting = "${greetingFor(now.hour)}, $name.",
            dateLabel = today.format(LONG_DATE_FORMAT),
            week = WeekStrip(
                days = weekDays(user, calendarId, today, now),
                streak = stats.currentStreak,
                toConfirmCount = waiting.size
            ),
            wrapUp = wrapUp,
            recentNotes = recentNotes(user, today),
            month = MonthSummary(
                trainedLabel = stats.totalTrainedLabel,
                attended = stats.counts.attended,
                attendanceRatePercent = stats.attendanceRatePercent,
                byCategory = stats.byCategory
            ),
            continueItems = continueItems(user, today)
        )
    }

    /** Sessions still marked planned after they ended, newest first. */
    private fun awaitingConfirmation(user: AppUser, calendarId: UUID?, now: LocalDateTime): List<TrainingEvent> =
        trainingEventService.findByCurrentUser(awaitingConfirmation = true, calendarId = calendarId)
            .filter { it.isAwaitingConfirmationFor(user, now) }
            .distinctBy { it.icalUid ?: it.id }
            .sortedByDescending { it.startTime }

    private fun nextSession(user: AppUser, calendarId: UUID?, now: LocalDateTime): TrainingEvent? =
        trainingEventService.findInRange(now, now.plusDays(NEXT_SESSION_HORIZON_DAYS), calendarId)
            .filter { it.attendanceFor(user) == AttendanceStatus.PLANNED && it.endTime.isAfter(now) }
            .minByOrNull { it.startTime }

    private fun weekDays(user: AppUser, calendarId: UUID?, today: LocalDate, now: LocalDateTime): List<WeekDay> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sessions = trainingEventService
            .findInRange(monday.atStartOfDay(), monday.plusDays(7).atStartOfDay(), calendarId)
            .distinctBy { it.icalUid ?: it.id }
        return (0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            val states = sessions
                .filter { it.startTime.toLocalDate() == date }
                .map { stateOf(it, user, now) }
            WeekDay(
                date = date,
                weekdayLabel = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                dayOfMonth = date.dayOfMonth,
                today = date == today,
                // The enum is declared highest precedence first, so the minimum ordinal wins.
                state = states.minByOrNull { it.ordinal } ?: WeekDayState.NONE
            )
        }
    }

    private fun stateOf(event: TrainingEvent, user: AppUser, now: LocalDateTime): WeekDayState = when {
        event.isAwaitingConfirmationFor(user, now) -> WeekDayState.UNCONFIRMED
        event.attendanceFor(user) == AttendanceStatus.ATTENDED -> WeekDayState.ATTENDED
        event.attendanceFor(user) == AttendanceStatus.PLANNED -> WeekDayState.PLANNED
        else -> WeekDayState.NONE
    }

    private fun toCard(event: TrainingEvent) = SessionCard(
        id = event.id!!,
        title = event.title,
        dateLabel = event.startTime.format(DATE_FORMAT),
        timeLabel = "${event.startTime.format(TIME_FORMAT)}–${event.endTime.format(TIME_FORMAT)}",
        segments = event.segments.map {
            SessionSegment(
                label = it.danceCategory?.name ?: "Other",
                durationLabel = formatMinutes(it.durationMinutes.toLong())
            )
        },
        noteId = event.material?.id
    )

    private fun recentNotes(user: AppUser, today: LocalDate): List<RecentNote> =
        materialRepository.findRecentByOwner(user, PageRequest.of(0, RECENT_NOTES)).map { note ->
            RecentNote(
                id = note.id!!,
                title = note.name,
                danceLabel = note.danceType?.name,
                whenLabel = relativeDate((note.updatedAt ?: note.createdAt).toLocalDate(), today),
                excerpt = excerptOf(note.description),
                figures = pinnedFigures(note)
            )
        }

    /**
     * The timing is the figure's `alternativeTiming`, the same field the note page's pinned-figure
     * chip shows, so a figure reads the same on both screens. A figure without one is still
     * listed, without a value: a missing timing must not render as `[]`.
     */
    private fun pinnedFigures(note: Material): List<PinnedFigure> =
        note.figures.mapNotNull { pin ->
            val figure = pin.danceFigure ?: return@mapNotNull null
            PinnedFigure(name = figure.name, timing = figure.alternativeTiming?.trim()?.ifEmpty { null })
        }

    private fun excerptOf(description: String?): String {
        val text = Jsoup.parse(description.orEmpty()).text().trim()
        if (text.length <= EXCERPT_LENGTH) return text
        return text.take(EXCERPT_LENGTH).substringBeforeLast(' ').trimEnd(',', '.', ';', ':', '-') + "…"
    }

    /** The user's most recently touched choreographies and collections, mixed and newest first. */
    private fun continueItems(user: AppUser, today: LocalDate): List<ContinueItem> {
        val choreographies = choreographyRepository.findAllByOwner(user)
            .sortedByDescending { it.updatedAt }
            .take(CONTINUE_ITEMS)
            .map {
                val figures = it.entries.count { entry -> entry.entryType == EntryType.FIGURE }
                it.updatedAt to ContinueItem(
                    kind = ContinueKind.CHOREOGRAPHY,
                    id = it.id!!,
                    name = it.name,
                    href = "/choreographies/${it.id}",
                    detail = "${figures} ${if (figures == 1) "figure" else "figures"} · edited " +
                        relativeDate(it.updatedAt.toLocalDate(), today)
                )
            }
        // A collection is a saved filter with no edit time of its own, so creation date stands in.
        val collections = customListRepository.findAllByOwner(user)
            .sortedByDescending { it.createdAt }
            .take(CONTINUE_ITEMS)
            .map {
                it.createdAt to ContinueItem(
                    kind = ContinueKind.COLLECTION,
                    id = it.id!!,
                    name = it.name,
                    href = "/lists/${it.id}",
                    detail = "created " + relativeDate(it.createdAt.toLocalDate(), today)
                )
            }
        return (choreographies + collections)
            .sortedByDescending { it.first }
            .take(CONTINUE_ITEMS)
            .map { it.second }
    }

    private fun relativeDate(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "last week"
            date.year == today.year -> date.format(SHORT_DATE_FORMAT)
            else -> date.format(FULL_DATE_FORMAT)
        }
    }
}
