package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CalendarMember
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import java.util.UUID

interface CalendarMemberService {

    fun findMembers(calendarId: UUID, actor: AppUser): List<CalendarMember>

    fun inviteMember(calendarId: UUID, identifier: String, actor: AppUser): CalendarMember

    fun removeMember(calendarId: UUID, memberId: UUID, actor: AppUser)

    fun getPendingInvitesFor(user: AppUser): List<CalendarMember>

    fun countPendingInvitesFor(user: AppUser): Long

    fun acceptInvite(memberId: UUID, user: AppUser): CalendarMember

    fun declineInvite(memberId: UUID, user: AppUser)

    fun subscribe(calendarId: UUID, user: AppUser): CalendarMember

    fun unsubscribe(calendarId: UUID, user: AppUser)

    fun claimInvitesFor(user: AppUser)

    fun isMember(calendarId: UUID, user: AppUser): Boolean
}
