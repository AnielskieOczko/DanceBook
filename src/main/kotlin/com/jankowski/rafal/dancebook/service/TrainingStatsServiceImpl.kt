package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Every figure on the training dashboard.
 *
 * The whole of the user's history is loaded once and reduced in memory: the streak spans
 * all of it regardless of the selected period, the volumes are tiny, and a sequential walk
 * is far clearer here than the SQL that would express it.
 */
@Service
@Transactional(readOnly = true)
class TrainingStatsServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val appUserService: AppUserService
) : TrainingStatsService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingStatsServiceImpl::class.java)
        /** Shown as its own slice, so the chart reconciles with the hours card. */
        private const val UNASSIGNED_LABEL = "Unassigned"
    }

    override fun statsForCurrentUser(period: StatsPeriod): TrainingStats {
        val currentUser = appUserService.getCurrentUser()
        val allEvents = trainingEventRepository.findAllByCreatedBy(currentUser)
        log.debug("Computing {} training stats for user '{}'", period, currentUser.username)

        // One "today" for the whole computation: a render straddling midnight must not
        // judge some events against one day and the rest against the next.
        val today = LocalDate.now()
        val inPeriod = allEvents.filter { period.contains(it.startTime, today) }
        val attended = inPeriod.filter { it.attendanceStatus == AttendanceStatus.ATTENDED }
        val counts = countsOf(inPeriod)

        return TrainingStats(
            period = period,
            totalMinutesTrained = attended.sumOf { it.durationMinutes },
            counts = counts,
            attendanceRatePercent = attendanceRate(counts),
            currentStreak = currentStreak(allEvents),
            byCategory = categoryBreakdown(attended),
            byEventType = eventTypeBreakdown(attended)
        )
    }

    /**
     * Unconfirmed is tested first so a past session still marked planned reads as needing
     * confirmation rather than as upcoming — the same precedence `TrainingEventPalette`
     * applies when it picks a colour.
     */
    private fun countsOf(events: List<TrainingEvent>): SessionCounts {
        var upcoming = 0
        var unconfirmed = 0
        var attended = 0
        var skipped = 0
        var cancelled = 0
        for (event in events) {
            when {
                event.isAwaitingConfirmation -> unconfirmed++
                event.attendanceStatus == AttendanceStatus.ATTENDED -> attended++
                event.attendanceStatus == AttendanceStatus.SKIPPED -> skipped++
                event.attendanceStatus == AttendanceStatus.CANCELLED -> cancelled++
                else -> upcoming++
            }
        }
        return SessionCounts(upcoming, unconfirmed, attended, skipped, cancelled)
    }

    /** Null rather than zero when nothing has been decided: no data is not a bad record. */
    private fun attendanceRate(counts: SessionCounts): Int? {
        val decided = counts.attended + counts.skipped
        if (decided == 0) return null
        return (counts.attended * 100.0 / decided).roundToInt()
    }

    /**
     * Consecutive attended sessions counting back from the most recent.
     *
     * Walks the user's whole history rather than the selected period: a streak cut off at
     * a window boundary would report a number that is not the user's streak. Cancelled
     * sessions and sessions still awaiting confirmation are stepped over, because neither
     * is evidence of a missed session; only a skip ends the run.
     *
     * Phase 4 (badges) reuses this walk, which is why it stands alone.
     */
    private fun currentStreak(allEvents: List<TrainingEvent>): Int {
        var streak = 0
        for (event in allEvents.sortedByDescending { it.startTime }) {
            if (event.isAwaitingConfirmation) continue
            when (event.attendanceStatus) {
                AttendanceStatus.ATTENDED -> streak++
                AttendanceStatus.SKIPPED -> return streak
                else -> continue
            }
        }
        return streak
    }

    /**
     * Style time across attended sessions, with everything left over gathered into a
     * trailing "Unassigned" slice.
     *
     * Segments are optional and may cover less than a session's wall clock -- warm-ups and
     * breaks -- while competitions and camps usually carry none at all. Without the
     * remainder slice the chart would total fewer hours than the card above it claims.
     */
    private fun categoryBreakdown(attended: List<TrainingEvent>): List<BreakdownSlice> {
        val minutesByCategory = mutableMapOf<String, Long>()
        val sessionsByCategory = mutableMapOf<String, Int>()

        for (event in attended) {
            val categoriesTouched = mutableSetOf<String>()
            for (segment in event.segments) {
                val name = segment.danceCategory?.name ?: continue
                minutesByCategory.merge(name, segment.durationMinutes.toLong(), Long::plus)
                categoriesTouched += name
            }
            categoriesTouched.forEach { sessionsByCategory.merge(it, 1, Int::plus) }
        }

        val slices = minutesByCategory.keys.sorted().mapIndexed { index, name ->
            BreakdownSlice(
                label = name,
                minutes = minutesByCategory.getValue(name),
                sessionCount = sessionsByCategory[name] ?: 0,
                color = TrainingEventPalette.chartColor(index)
            )
        }

        // Negative is unreachable: TrainingEventServiceImpl (applySegments and reschedule)
        // enforces at write time that segment minutes never exceed a session's wall clock,
        // so this guard only ever discards the zero case.
        val unassigned = attended.sumOf { it.durationMinutes } - minutesByCategory.values.sum()
        if (unassigned <= 0) return slices
        return slices + BreakdownSlice(
            label = UNASSIGNED_LABEL,
            minutes = unassigned,
            sessionCount = 0,
            color = TrainingEventPalette.UNASSIGNED_COLOR
        )
    }

    /**
     * Attended sessions grouped by kind, in the enum's own order so the chart does not
     * reshuffle as the data changes. Labels are the raw enum names, matching how the
     * training list already renders an event's type.
     */
    private fun eventTypeBreakdown(attended: List<TrainingEvent>): List<BreakdownSlice> =
        attended.groupBy { it.eventType }
            .toList()
            .sortedBy { (type, _) -> type.ordinal }
            .mapIndexed { index, (type, events) ->
                BreakdownSlice(
                    label = type.name,
                    minutes = events.sumOf { it.durationMinutes },
                    sessionCount = events.size,
                    color = TrainingEventPalette.chartColor(index)
                )
            }
}
