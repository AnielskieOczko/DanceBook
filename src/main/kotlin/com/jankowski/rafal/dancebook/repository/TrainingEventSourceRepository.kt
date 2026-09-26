package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.TrainingEventSource
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingEventSourceRepository : JpaRepository<TrainingEventSource, UUID> {

    fun findByTrainingEventIdAndCalendarSourceId(trainingEventId: UUID, calendarSourceId: UUID): TrainingEventSource?

    fun findByGoogleEventId(googleEventId: String): List<TrainingEventSource>

    fun findByCalendarSourceIdAndGoogleEventId(calendarSourceId: UUID, googleEventId: String): TrainingEventSource?

    fun findAllByCalendarSourceId(calendarSourceId: UUID): List<TrainingEventSource>

    fun findAllByTrainingEventId(trainingEventId: UUID): List<TrainingEventSource>
}
