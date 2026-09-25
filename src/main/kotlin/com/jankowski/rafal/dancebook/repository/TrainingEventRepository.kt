package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@Repository
interface TrainingEventRepository : JpaRepository<TrainingEvent, UUID>, JpaSpecificationExecutor<TrainingEvent> {

    @EntityGraph(attributePaths = ["calendar", "attendances"], type = EntityGraph.EntityGraphType.LOAD)
    override fun findById(id: UUID): Optional<TrainingEvent>

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

    /** The same window, scoped to one calendar. */
    fun findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
        createdBy: AppUser,
        calendarId: UUID,
        startBefore: LocalDateTime,
        endAfter: LocalDateTime
    ): List<TrainingEvent>

    fun findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
        series: TrainingSeries,
        startTime: LocalDateTime
    ): List<TrainingEvent>

    fun findAllBySeriesOrderByStartTimeAsc(series: TrainingSeries): List<TrainingEvent>

    fun countBySeries(series: TrainingSeries): Long

    /**
     * One window of the user's history, newest first — future sessions, then today, then the past.
     *
     * Carries no entity graph on purpose. Hibernate cannot paginate a collection fetch in SQL and
     * falls back to loading everything and slicing in memory, which is exactly what the timeline's
     * windowing exists to avoid; [findAllByIdIn] loads the segments for the window instead.
     */
    fun findAllByCreatedByOrderByStartTimeDesc(createdBy: AppUser, pageable: Pageable): List<TrainingEvent>

    /** The same paged probe, scoped to one calendar. */
    fun findAllByCreatedByAndCalendarIdOrderByStartTimeDesc(
        createdBy: AppUser,
        calendarId: UUID,
        pageable: Pageable
    ): List<TrainingEvent>

    /**
     * The second half of that two-step fetch: the same sessions again, with segments and their
     * categories attached. An `IN` query has no inherent order, so the caller re-sorts.
     */
    @EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
    fun findAllByIdIn(ids: Collection<UUID>): List<TrainingEvent>

    /** Calendar ids that at least one session points at; drives which calendars stay selectable. */
    @Query("select distinct e.calendar.id from TrainingEvent e where e.calendar is not null")
    fun calendarIdsInUse(): List<UUID>

    /** Number of sessions belonging to a specific calendar. */
    fun countByCalendarId(calendarId: UUID): Long

    /** All sessions belonging to a specific calendar. */
    fun findAllByCalendarId(calendarId: UUID): List<TrainingEvent>

    /**
     * Points every event with no calendar at [calendar]. Used by the startup backfill.
     *
     * `flushAutomatically` is load-bearing: the bootstrap saves a brand new calendar and
     * backfills in the same transaction, so without a flush the INSERT is still pending in
     * the persistence context while this bulk UPDATE goes to the database as raw SQL —
     * and Postgres rejects the FK against a row it cannot see yet.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TrainingEvent e set e.calendar = :calendar where e.calendar is null")
    fun assignMissingCalendar(calendar: TrainingCalendar): Int
}

