package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingCalendarRepository : JpaRepository<TrainingCalendar, UUID> {

    fun findByIsDefaultTrue(): TrainingCalendar?

    fun findByGoogleCalendarId(googleCalendarId: String): TrainingCalendar?

    fun findAllByOrderByDisplayNameAsc(): List<TrainingCalendar>

    fun findAllByEnabledTrueOrderByDisplayNameAsc(): List<TrainingCalendar>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TrainingCalendar c set c.isDefault = false where c.id != :id")
    fun clearDefaultExcept(id: UUID): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TrainingCalendar c set c.isDefault = true, c.enabled = true where c.id = :id")
    fun markDefault(id: UUID): Int
}
