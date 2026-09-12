package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@Repository
interface TrainingEventRepository : JpaRepository<TrainingEvent, UUID>, JpaSpecificationExecutor<TrainingEvent> {

    fun findAllByCreatedByOrderByStartTimeDesc(createdBy: AppUser): List<TrainingEvent>

    fun findByGoogleEventId(googleEventId: String): Optional<TrainingEvent>

    /**
     * Occurrences of a series at or after a cut-off, used by "this and following" to
     * regenerate the future while leaving completed sessions untouched.
     */
    /** Sessions overlapping a window: they start before it ends and end after it starts. */
    fun findAllByCreatedByAndStartTimeLessThanAndEndTimeGreaterThan(
        createdBy: AppUser,
        startBefore: LocalDateTime,
        endAfter: LocalDateTime
    ): List<TrainingEvent>

    fun findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
        series: TrainingSeries,
        startTime: LocalDateTime
    ): List<TrainingEvent>

    /**
     * One window of the user's history, newest first — future sessions, then today, then the past.
     *
     * Carries no entity graph on purpose. Hibernate cannot paginate a collection fetch in SQL and
     * falls back to loading everything and slicing in memory, which is exactly what the timeline's
     * windowing exists to avoid; [findAllByIdIn] loads the segments for the window instead.
     */
    fun findAllByCreatedByOrderByStartTimeDesc(createdBy: AppUser, pageable: Pageable): List<TrainingEvent>

    /**
     * The second half of that two-step fetch: the same sessions again, with segments and their
     * categories attached. An `IN` query has no inherent order, so the caller re-sorts.
     */
    @EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
    fun findAllByIdIn(ids: Collection<UUID>): List<TrainingEvent>
}
