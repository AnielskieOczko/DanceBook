package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingRecord
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingRecordRepository : JpaRepository<TrainingRecord, UUID> {

    /** The record of one session, if it has been confirmed. `training_event_id` is unique. */
    fun findByTrainingEventId(trainingEventId: UUID): TrainingRecord?

    /**
     * The records of a batch of sessions, for the bulk deletes that orphan a whole series at
     * once. Series generation is capped at 52 occurrences, so the `IN` list is always small.
     */
    fun findAllByTrainingEventIdIn(trainingEventIds: Collection<UUID>): List<TrainingRecord>

    /**
     * A user's whole confirmed history, newest first, with the style breakdown already
     * loaded. Both the statistics page and the history page walk every segment, so the
     * entity graph is what keeps this from issuing a query per record.
     */
    @EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
    fun findAllByCreatedByOrderByOccurredAtDesc(createdBy: AppUser): List<TrainingRecord>
}
