package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingCalendarRepository : JpaRepository<TrainingCalendar, UUID>, JpaSpecificationExecutor<TrainingCalendar> {

    @Query("select distinct c from TrainingCalendar c join c.sources s where s.googleCalendarId = :googleCalendarId")
    fun findByGoogleCalendarId(googleCalendarId: String): TrainingCalendar?

    fun findAllByOrderByDisplayNameAsc(): List<TrainingCalendar>

    fun findAllByEnabledTrueOrderByDisplayNameAsc(): List<TrainingCalendar>

    fun findAllByOwnerOrderByDisplayNameAsc(owner: AppUser): List<TrainingCalendar>
}
