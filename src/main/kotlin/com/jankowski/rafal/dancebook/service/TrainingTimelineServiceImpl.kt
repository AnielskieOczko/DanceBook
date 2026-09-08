package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.dto.TrainingTimelineEntry
import com.jankowski.rafal.dancebook.dto.TrainingTimelineMonth
import com.jankowski.rafal.dancebook.dto.groupByMonth
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * The training history as one scroll: what is coming, then today, then everything behind it.
 *
 * Unlike the statistics page this does not load the whole history — a timeline is read from the
 * top and rarely to the end, so it is windowed and the older windows are fetched only if the user
 * asks for them.
 */
@Service
@Transactional(readOnly = true)
class TrainingTimelineServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val appUserService: AppUserService
) : TrainingTimelineService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingTimelineServiceImpl::class.java)

        /** Sessions per window. Roughly a season of twice-weekly training. */
        const val PAGE_SIZE = 25
    }

    override fun timelineForCurrentUser(page: Int): TrainingTimeline {
        val currentUser = appUserService.getCurrentUser()
        log.debug("Building training timeline page {} for user '{}'", page, currentUser.username)

        // One row beyond the window: if it comes back, there is another window behind this one.
        // Cheaper and simpler than a second count query, and the extra row is never rendered.
        val probed = trainingEventRepository.findAllByCreatedByOrderByStartTimeDesc(
            currentUser,
            PageRequest.of(page, PAGE_SIZE + 1)
        )
        val hasMore = probed.size > PAGE_SIZE
        val window = probed.take(PAGE_SIZE)

        val events = withSegments(window)

        // One "now" for the whole window, so a render straddling midnight cannot place one
        // session in the future and the next in the past by the same instant.
        val now = LocalDateTime.now()
        val months = toMonths(events, now, isFirstPage = page == 0)

        return TrainingTimeline(
            months = months,
            hasMore = hasMore,
            nextPage = page + 1,
            lastMonthLabel = months.lastOrNull()?.label,
            isFirstPage = page == 0
        )
    }

    /**
     * Re-reads the window through the entity graph so the page can draw each session's styles
     * without a query per row. Paging and collection-fetching cannot go in one query — Hibernate
     * would page in memory — so they go in two, and this one restores the order the `IN` lost.
     */
    private fun withSegments(window: List<TrainingEvent>): List<TrainingEvent> {
        if (window.isEmpty()) return emptyList()
        val ids = window.mapNotNull { it.id }
        return trainingEventRepository.findAllByIdIn(ids).sortedByDescending { it.startTime }
    }

    /**
     * Groups the window into months and marks the "today" divider.
     *
     * The divider belongs above the first session that is no longer ahead of us, and only on the
     * first window: later windows are entirely in the past, so a second divider there would be a
     * lie about where the present sits.
     */
    private fun toMonths(
        events: List<TrainingEvent>,
        now: LocalDateTime,
        isFirstPage: Boolean
    ): List<TrainingTimelineMonth> {
        val boundary = if (isFirstPage) events.firstOrNull { !it.startTime.isAfter(now) } else null

        return groupByMonth(events).map { group ->
            TrainingTimelineMonth(
                label = group.label,
                entries = group.rows.map { row ->
                    TrainingTimelineEntry(
                        row = row,
                        isFuture = row.event.startTime.isAfter(now),
                        startsTodayBoundary = row.event === boundary
                    )
                },
                totalMinutes = group.totalMinutes
            )
        }
    }
}
