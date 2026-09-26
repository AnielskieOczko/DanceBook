package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CalendarMember
import com.jankowski.rafal.dancebook.model.CalendarMemberState
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CalendarMemberRepository : JpaRepository<CalendarMember, UUID>, JpaSpecificationExecutor<CalendarMember> {

    fun findAllByCalendarOrderByCreatedAtAsc(calendar: TrainingCalendar): List<CalendarMember>

    fun findAllByCalendarIdOrderByCreatedAtAsc(calendarId: UUID): List<CalendarMember>

    fun findByCalendarIdAndUserId(calendarId: UUID, userId: UUID): CalendarMember?

    fun findByCalendarAndUser(calendar: TrainingCalendar, user: AppUser): CalendarMember?

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = ["calendar", "calendar.owner", "user"])
    fun findAllByUserAndStateOrderByCreatedAtDesc(user: AppUser, state: CalendarMemberState): List<CalendarMember>

    fun findAllByUserIdIsNullAndInvitedEmailIgnoreCase(invitedEmail: String): List<CalendarMember>

    fun findByCalendarIdAndInvitedEmailIgnoreCase(calendarId: UUID, invitedEmail: String): CalendarMember?

    fun existsByCalendarIdAndUserId(calendarId: UUID, userId: UUID): Boolean

    fun existsByCalendarIdAndUserIdAndState(calendarId: UUID, userId: UUID, state: CalendarMemberState): Boolean

    fun existsByCalendarIdAndInvitedEmailIgnoreCaseAndState(calendarId: UUID, invitedEmail: String, state: CalendarMemberState): Boolean
}
