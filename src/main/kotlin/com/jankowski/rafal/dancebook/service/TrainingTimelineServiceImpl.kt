package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AppLocales
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRow
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.dto.TrainingTimelineDot
import com.jankowski.rafal.dancebook.dto.TrainingTimelineEntry
import com.jankowski.rafal.dancebook.dto.TrainingTimelineMonth
import com.jankowski.rafal.dancebook.dto.TrainingTimelineStyleRollup
import com.jankowski.rafal.dancebook.dto.TrainingTimelineWeek
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventSpecification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

/**
 * The training history as a vertical journey feed: newest first, grouped into weeks and months
 * with weekly rollups and streak markers.
 *
 * Windowed with PAGE_SIZE per window, fetched only if the user asks for older history.
 */
@Service
@Transactional(readOnly = true)
class TrainingTimelineServiceImpl @Autowired constructor(
    private val trainingEventRepository: TrainingEventRepository,
    private val appUserService: AppUserService,
    private val trainingStatsService: TrainingStatsService? = null
) : TrainingTimelineService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingTimelineServiceImpl::class.java)

        /** Sessions per window. Roughly a season of twice-weekly training. */
        const val PAGE_SIZE = 25
    }

    override fun timelineForCurrentUser(page: Int, calendarId: UUID?): TrainingTimeline {
        val currentUser = appUserService.getCurrentUser()
        log.debug("Building training timeline page {} for user '{}'", page, currentUser.username)

        // One row beyond the window: if it comes back, there is another window behind this one.
        val pageable = PageRequest.of(page, PAGE_SIZE + 1, Sort.by(Sort.Direction.DESC, "startTime"))
        // Sessions on every calendar the user owns or follows (#155), not only ones they created.
        val probed = trainingEventRepository.findAll(
            TrainingEventSpecification.withFilters(user = currentUser, calendarId = calendarId),
            pageable
        ).content
        val hasMore = probed.size > PAGE_SIZE
        val window = probed.take(PAGE_SIZE)

        val events = withSegments(window)

        // One "now" for the whole window, so a render straddling midnight cannot place one
        // session in the future and the next in the past by the same instant.
        val now = LocalDateTime.now()
        val stats = trainingStatsService?.statsForCurrentUser(StatsPeriod.ALL_TIME, calendarId)
        val months = toMonths(
            events = events,
            now = now,
            isFirstPage = page == 0,
            hasMore = hasMore,
            page = page,
            calendarId = calendarId,
            currentUser = currentUser,
            currentStreak = stats?.currentStreak ?: 0
        )

        return TrainingTimeline(
            months = months,
            hasMore = hasMore,
            nextPage = page + 1,
            lastMonthLabel = months.lastOrNull()?.label,
            isFirstPage = page == 0,
            stats = stats
        )
    }

    /**
     * Re-reads the window through the entity graph so the page can draw each session's styles
     * without a query per row.
     */
    private fun withSegments(window: List<TrainingEvent>): List<TrainingEvent> {
        if (window.isEmpty()) return emptyList()
        val ids = window.mapNotNull { it.id }
        return trainingEventRepository.findAllByIdIn(ids).sortedByDescending { it.startTime }
    }

    /**
     * Groups the window into weeks as the primary feed unit, calculating weekly rollups and
     * streak markers, then grouping weeks by month based on the week's newest session.
     */
    private fun toMonths(
        events: List<TrainingEvent>,
        now: LocalDateTime,
        isFirstPage: Boolean,
        hasMore: Boolean,
        page: Int,
        calendarId: UUID?,
        currentUser: AppUser,
        currentStreak: Int
    ): List<TrainingTimelineMonth> {
        if (events.isEmpty()) return emptyList()

        val boundary = if (isFirstPage) events.firstOrNull { !it.startTime.isAfter(now) } else null
        val locale = AppLocales.parseLocale(currentUser.locale) ?: LocaleContextHolder.getLocale()
        val today = now.toLocalDate()
        val todayFormatted = now.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)

        val pastEvents = events.filter { !it.startTime.isAfter(now) }
        val latestAttended = pastEvents.firstOrNull { it.attendanceFor(currentUser) == AttendanceStatus.ATTENDED }

        val entries = events.map { event ->
            val isLatestAttendedWithStreak = isFirstPage && currentStreak > 0 && event === latestAttended
            TrainingTimelineEntry(
                row = TrainingEventRow(event, TrainingEventPalette.swatchFor(event, currentUser)),
                isFuture = event.startTime.isAfter(now),
                startsTodayBoundary = event === boundary,
                activeStreak = if (isLatestAttendedWithStreak) currentStreak else null,
                streakReachedDate = if (isLatestAttendedWithStreak) event.startTime.format(dateFormatter) else null,
                todayFormatted = if (event === boundary) todayFormatted else null
            )
        }

        // Group entries into ISO weeks (Monday to Sunday), preserving descending order
        val byMonday = LinkedHashMap<LocalDate, MutableList<TrainingTimelineEntry>>()
        for (entry in entries) {
            val monday = entry.event.startTime.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            byMonday.computeIfAbsent(monday) { mutableListOf() }.add(entry)
        }

        val weeks = byMonday.map { (monday, weekEntries) ->
            val sunday = monday.plusDays(6)
            val weekNumber = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val isCurrentWeek = !today.isBefore(monday) && !today.isAfter(sunday)
            val dateRangeLabel = formatDateRange(monday, sunday, locale)

            // For complete rollups even when a week straddles window boundaries:
            val fullWeekEvents = if (hasMore || page > 0) {
                val weekSpec = TrainingEventSpecification
                    .withFilters(user = currentUser, calendarId = calendarId)
                    .and(TrainingEventSpecification.overlapping(monday.atStartOfDay(), sunday.atTime(LocalTime.MAX)))
                val fetched = trainingEventRepository.findAll(weekSpec)
                if (fetched.isNotEmpty()) fetched else weekEntries.map { it.event }
            } else {
                weekEntries.map { it.event }
            }

            val attendedEvents = fullWeekEvents.filter { it.attendanceFor(currentUser) == AttendanceStatus.ATTENDED }
            val totalMinutesTrained = attendedEvents.sumOf { it.durationMinutes }
            val attendedCount = attendedEvents.size
            val skippedCount = fullWeekEvents.count { it.attendanceFor(currentUser) == AttendanceStatus.SKIPPED }
            val unconfirmedCount = fullWeekEvents.count { it.isAwaitingConfirmationFor(currentUser, now) }
            val totalDecidedCount = attendedCount + skippedCount + unconfirmedCount

            val styles = computeStylesRollup(attendedEvents)
            val dots = fullWeekEvents.sortedBy { it.startTime }.map { ev ->
                val status = ev.attendanceFor(currentUser)
                val isAwaiting = ev.isAwaitingConfirmationFor(currentUser, now)
                val statusClass = when {
                    isAwaiting -> "u"
                    status == AttendanceStatus.ATTENDED -> "a"
                    status == AttendanceStatus.SKIPPED -> "s"
                    status == AttendanceStatus.CANCELLED -> "s"
                    else -> "f"
                }
                TrainingTimelineDot(title = ev.title, statusClass = statusClass)
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
                dots = dots,
                totalWeekSessions = fullWeekEvents.size
            )
        }

        // Assign each week to a month decided by the week's newest session
        val weeksByMonth = LinkedHashMap<LocalDate, MutableList<TrainingTimelineWeek>>()
        for (week in weeks) {
            val newestEvent = week.entries.first().event
            val monthDate = newestEvent.startTime.toLocalDate().withDayOfMonth(1)
            weeksByMonth.computeIfAbsent(monthDate) { mutableListOf() }.add(week)
        }

        // Polish nominative month format: "LLLL yyyy" -> "listopad 2026", English -> "November 2026"
        val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        return weeksByMonth.map { (monthDate, monthWeeks) ->
            val monthEntries = monthWeeks.flatMap { it.entries }
            val monthTotalMinutes = monthWeeks.sumOf { it.totalMinutes }
            TrainingTimelineMonth(
                label = monthDate.format(monthFormatter),
                entries = monthEntries,
                totalMinutes = monthTotalMinutes,
                weeks = monthWeeks
            )
        }
    }

    private fun computeStylesRollup(attendedEvents: List<TrainingEvent>): List<TrainingTimelineStyleRollup> {
        val styleMins = LinkedHashMap<String, Long>()
        for (event in attendedEvents) {
            for (seg in event.segments) {
                val name = seg.danceCategory?.name ?: ""
                if (name.isNotBlank()) {
                    styleMins[name] = (styleMins[name] ?: 0L) + seg.durationMinutes
                }
            }
        }
        return styleMins.map { (name, mins) -> TrainingTimelineStyleRollup(name, mins) }
    }

    private fun formatDateRange(monday: LocalDate, sunday: LocalDate, locale: Locale): String {
        val formatter = if (locale.language == "pl") {
            DateTimeFormatter.ofPattern("d MMM", locale)
        } else {
            DateTimeFormatter.ofPattern("MMM d", locale)
        }
        return "${monday.format(formatter)} – ${sunday.format(formatter)}"
    }
}
