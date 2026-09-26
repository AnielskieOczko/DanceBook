package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.CalendarSource
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CalendarSourceRepository : JpaRepository<CalendarSource, UUID> {

    fun findByCalendarIdAndGoogleCalendarId(calendarId: UUID, googleCalendarId: String): CalendarSource?

    fun findByGoogleCalendarId(googleCalendarId: String): List<CalendarSource>

    fun findAllByCalendarId(calendarId: UUID): List<CalendarSource>

    fun findByCalendarIdAndIsWriteTargetTrue(calendarId: UUID): CalendarSource?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CalendarSource cs set cs.isWriteTarget = false where cs.calendar.id = :calendarId")
    fun clearWriteTargets(calendarId: UUID): Int
}
