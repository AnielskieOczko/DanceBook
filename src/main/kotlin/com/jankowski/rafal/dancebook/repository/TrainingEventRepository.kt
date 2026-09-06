package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
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
    fun findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
        series: TrainingSeries,
        startTime: LocalDateTime
    ): List<TrainingEvent>
}
