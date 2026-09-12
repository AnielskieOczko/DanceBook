package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Every figure on the training dashboard.
 *
 * Deliberately hybrid, and the split is the point. Hours, the attended and skipped counts,
 * the attendance rate, the streak and both breakdowns are facts about *history* and come from
 * `training_record`, which outlives the sessions it describes — so tidying the calendar no
 * longer rewrites the past. Upcoming, unconfirmed and cancelled are facts about the
 * *schedule* and stay on `training_event`, because they have no business outliving the
 * sessions they describe.
 *
 * The whole of the user's history is loaded once and reduced in memory: the streak spans all
 * of it regardless of the selected period, the volumes are tiny, and a sequential walk is far
 * clearer here than the SQL that would express it.
 */
@Service
@Transactional(readOnly = true)
class TrainingStatsServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingRecordRepository: TrainingRecordRepository,
    private val appUserService: AppUserService
) : TrainingStatsService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingStatsServiceImpl::class.java)
        /** Shown as its own slice, so the chart reconciles with the hours card. */
        private const val UNASSIGNED_LABEL = "Unassigned"
    }

    override fun statsForCurrentUser(period: StatsPeriod): TrainingStats {
        val currentUser = appUserService.getCurrentUser()
        val allRecords = trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser)
        val allEvents = trainingEventRepository.findAllByCreatedByOrderByStartTimeDesc(currentUser)
        log.debug("Computing {} training stats for user '{}'", period, currentUser.username)

        // One "today" for the whole computation: a render straddling midnight must not judge
        // some sessions against one day and the rest against the next.
        val today = LocalDate.now()
        val recordsInPeriod = allRecords.filter { period.contains(it.occurredAt, today) }
        val eventsInPeriod = allEvents.filter { period.contains(it.startTime, today) }
        val attended = recordsInPeriod.filter { it.outcome == TrainingOutcome.ATTENDED }
        val counts = countsOf(recordsInPeriod, eventsInPeriod)

        return TrainingStats(
            period = period,
            totalMinutesTrained = attended.sumOf { it.durationMinutes.toLong() },
            counts = counts,
            attendanceRatePercent = attendanceRate(counts),
            currentStreak = currentStreak(allRecords),
            byCategory = categoryBreakdown(attended),
            byEventType = eventTypeBreakdown(attended)
        )
    }

    /**
     * The hybrid in one method: the two confirmed buckets are counted from records, the three
     * schedule buckets from events.
     *
     * A confirmed event is skipped here rather than counted, because its record already counts
     * it — counting both would double every attended session. Unconfirmed is still tested
     * first, so a past session still marked planned reads as needing confirmation rather than
     * as upcoming, the same precedence `TrainingEventPalette` applies when it picks a colour.
     *
     * The buckets therefore stay disjoint: a session appears either as a record or as an
     * event, never as both, and an orphaned record appears with no event at all.
     */
    private fun countsOf(records: List<TrainingRecord>, events: List<TrainingEvent>): SessionCounts {
        var upcoming = 0
        var unconfirmed = 0
        var cancelled = 0
        for (event in events) {
            when {
                event.isAwaitingConfirmation -> unconfirmed++
                event.attendanceStatus == AttendanceStatus.ATTENDED -> Unit
                event.attendanceStatus == AttendanceStatus.SKIPPED -> Unit
                event.attendanceStatus == AttendanceStatus.CANCELLED -> cancelled++
                else -> upcoming++
            }
        }
        return SessionCounts(
            upcoming = upcoming,
            unconfirmed = unconfirmed,
            attended = records.count { it.outcome == TrainingOutcome.ATTENDED },
            skipped = records.count { it.outcome == TrainingOutcome.SKIPPED },
            cancelled = cancelled
        )
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
     * Records hold only confirmed outcomes, so this is now exactly what it claims to be: there
     * is no cancelled or unconfirmed session to step over, because neither is history. Only a
     * skip ends the run.
     *
     * Walks the user's whole history rather than the selected period: a streak cut off at a
     * window boundary would report a number that is not the user's streak.
     */
    private fun currentStreak(allRecords: List<TrainingRecord>): Int {
        var streak = 0
        for (record in allRecords.sortedByDescending { it.occurredAt }) {
            when (record.outcome) {
                TrainingOutcome.ATTENDED -> streak++
                TrainingOutcome.SKIPPED -> return streak
            }
        }
        return streak
    }

    /**
     * A slice of the style breakdown, identified the way the record identifies its category.
     *
     * Keyed on the category's id while the category exists, so renaming a style relabels all
     * of its history instead of splitting it into a before and an after — the bug the old
     * name-keyed breakdown had, which has already bitten this codebase once. Once the category
     * is deleted the id is gone and the snapshotted name is all there is to key on.
     */
    private data class CategoryKey(val id: UUID?, val label: String)

    /**
     * Reuses [TrainingRecordSegment.label] rather than re-deriving "which name wins" here:
     * that property is already exactly this rule (`danceCategory?.name ?: categoryName`), and
     * a second hand-written copy of it could drift from the first.
     */
    private fun keyOf(segment: TrainingRecordSegment): CategoryKey =
        CategoryKey(segment.danceCategory?.id, segment.label)

    /**
     * Style time across attended sessions, with everything left over gathered into a trailing
     * "Unassigned" slice.
     *
     * Segments are optional and may cover less than a session's recorded length — warm-ups and
     * breaks — while competitions and camps usually carry none at all. Without the remainder
     * slice the chart would total fewer hours than the card above it claims.
     */
    private fun categoryBreakdown(attended: List<TrainingRecord>): List<BreakdownSlice> {
        val minutesByCategory = mutableMapOf<CategoryKey, Long>()
        val sessionsByCategory = mutableMapOf<CategoryKey, Int>()

        for (record in attended) {
            val categoriesTouched = mutableSetOf<CategoryKey>()
            for (segment in record.segments) {
                val key = keyOf(segment)
                minutesByCategory.merge(key, segment.durationMinutes.toLong(), Long::plus)
                categoriesTouched += key
            }
            categoriesTouched.forEach { sessionsByCategory.merge(it, 1, Int::plus) }
        }

        val slices = minutesByCategory.keys.sortedBy { it.label }.mapIndexed { index, key ->
            BreakdownSlice(
                label = key.label,
                minutes = minutesByCategory.getValue(key),
                sessionCount = sessionsByCategory[key] ?: 0,
                color = TrainingEventPalette.chartColor(index)
            )
        }

        // Negative is unreachable: TrainingEventServiceImpl (applySegments and reschedule)
        // enforces at write time that segment minutes never exceed a session's wall clock, and
        // the record copies both, so this guard only ever discards the zero case.
        val unassigned = attended.sumOf { it.durationMinutes.toLong() } - minutesByCategory.values.sum()
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
     * reshuffle as the data changes. Labels are the raw enum names, matching how the training
     * list already renders an event's type.
     */
    private fun eventTypeBreakdown(attended: List<TrainingRecord>): List<BreakdownSlice> =
        attended.groupBy { it.eventType }
            .toList()
            .sortedBy { (type, _) -> type.ordinal }
            .mapIndexed { index, (type, records) ->
                BreakdownSlice(
                    label = type.name,
                    minutes = records.sumOf { it.durationMinutes.toLong() },
                    sessionCount = records.size,
                    color = TrainingEventPalette.chartColor(index)
                )
            }
}
