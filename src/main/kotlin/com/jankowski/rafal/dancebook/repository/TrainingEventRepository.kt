package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
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
     * Every session the user owns, with segments and their categories already loaded.
     *
     * The statistics page needs full history (the streak ignores the selected period) and
     * walks every segment, so the entity graph is what keeps this from issuing a query
     * per session.
     */
    @EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
    fun findAllByCreatedBy(createdBy: AppUser): List<TrainingEvent>
}
