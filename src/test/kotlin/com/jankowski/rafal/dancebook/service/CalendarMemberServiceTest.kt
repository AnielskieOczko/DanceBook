package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CalendarMember
import com.jankowski.rafal.dancebook.model.CalendarMemberRole
import com.jankowski.rafal.dancebook.model.CalendarMemberState
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.CalendarMemberRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.security.access.AccessDeniedException
import java.util.Optional
import java.util.UUID

class CalendarMemberServiceTest {

    private val calendarMemberRepository = mock(CalendarMemberRepository::class.java)
    private val trainingCalendarRepository = mock(TrainingCalendarRepository::class.java)
    private val appUserRepository = mock(AppUserRepository::class.java)

    private lateinit var service: CalendarMemberServiceImpl

    private lateinit var owner: AppUser
    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var calendar: TrainingCalendar

    @BeforeEach
    fun setUp() {
        owner = AppUser().apply {
            id = UUID.randomUUID()
            username = "owner"
            email = "owner@example.com"
            role = Role.USER
        }
        userA = AppUser().apply {
            id = UUID.randomUUID()
            username = "usera"
            email = "usera@example.com"
            role = Role.USER
        }
        userB = AppUser().apply {
            id = UUID.randomUUID()
            username = "userb"
            email = "userb@example.com"
            role = Role.USER
        }
        calendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "My Calendar"
            this.owner = this@CalendarMemberServiceTest.owner
            visibility = Visibility.PRIVATE
            enabled = true
        }

        `when`(trainingCalendarRepository.findById(calendar.id!!)).thenReturn(Optional.of(calendar))
        `when`(calendarMemberRepository.save(org.mockito.ArgumentMatchers.any(CalendarMember::class.java)))
            .thenAnswer { it.getArgument(0) }

        service = CalendarMemberServiceImpl(
            calendarMemberRepository,
            trainingCalendarRepository,
            appUserRepository
        )
    }

    @Test
    fun `inviting yourself by username throws IllegalArgumentException`() {
        `when`(appUserRepository.findByUsername("owner")).thenReturn(owner)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.inviteMember(calendar.id!!, "owner", owner)
        }
        assertEquals("You cannot invite yourself.", error.message)
    }

    @Test
    fun `inviting yourself by email throws IllegalArgumentException`() {
        `when`(appUserRepository.findByEmailIgnoreCase("owner@example.com")).thenReturn(owner)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.inviteMember(calendar.id!!, "owner@example.com", owner)
        }
        assertEquals("You cannot invite yourself.", error.message)
    }

    @Test
    fun `inviting unknown username throws IllegalArgumentException`() {
        `when`(appUserRepository.findByUsername("unknown")).thenReturn(null)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.inviteMember(calendar.id!!, "unknown", owner)
        }
        assertTrue(error.message!!.contains("User 'unknown' not found"))
    }

    @Test
    fun `duplicate invite by username throws IllegalArgumentException`() {
        `when`(appUserRepository.findByUsername("usera")).thenReturn(userA)
        `when`(calendarMemberRepository.existsByCalendarIdAndUserId(calendar.id!!, userA.id!!)).thenReturn(true)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.inviteMember(calendar.id!!, "usera", owner)
        }
        assertEquals("User is already a member or has an invitation.", error.message)
    }

    @Test
    fun `duplicate invite by email for non-registered user throws IllegalArgumentException`() {
        `when`(appUserRepository.findByEmailIgnoreCase("new@example.com")).thenReturn(null)
        `when`(
            calendarMemberRepository.existsByCalendarIdAndInvitedEmailIgnoreCaseAndState(
                calendar.id!!, "new@example.com", CalendarMemberState.INVITED
            )
        ).thenReturn(true)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.inviteMember(calendar.id!!, "new@example.com", owner)
        }
        assertEquals("An invitation has already been sent to this email.", error.message)
    }

    @Test
    fun `duplicate invite by email for registered user throws IllegalArgumentException`() {
        `when`(appUserRepository.findByEmailIgnoreCase("usera@example.com")).thenReturn(userA)
        `when`(calendarMemberRepository.existsByCalendarIdAndUserId(calendar.id!!, userA.id!!)).thenReturn(true)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.inviteMember(calendar.id!!, "usera@example.com", owner)
        }
        assertEquals("User is already a member or has an invitation.", error.message)
    }

    @Test
    fun `claiming email invite assigns user and saves`() {
        val invite = CalendarMember().apply {
            id = UUID.randomUUID()
            this.calendar = this@CalendarMemberServiceTest.calendar
            this.user = null
            this.invitedEmail = userA.email
            this.state = CalendarMemberState.INVITED
        }
        `when`(calendarMemberRepository.findAllByUserIdIsNullAndInvitedEmailIgnoreCase(userA.email!!))
            .thenReturn(listOf(invite))
        `when`(calendarMemberRepository.findByCalendarIdAndUserId(calendar.id!!, userA.id!!))
            .thenReturn(null)

        service.claimInvitesFor(userA)

        assertEquals(userA, invite.user)
        verify(calendarMemberRepository).save(invite)
    }

    @Test
    fun `claiming email invite when user is already a member deletes unclaimed invite`() {
        val invite = CalendarMember().apply {
            id = UUID.randomUUID()
            this.calendar = this@CalendarMemberServiceTest.calendar
            this.user = null
            this.invitedEmail = userA.email
            this.state = CalendarMemberState.INVITED
        }
        val existingActive = CalendarMember().apply {
            id = UUID.randomUUID()
            this.calendar = this@CalendarMemberServiceTest.calendar
            this.user = userA
            this.state = CalendarMemberState.ACTIVE
        }
        `when`(calendarMemberRepository.findAllByUserIdIsNullAndInvitedEmailIgnoreCase(userA.email!!))
            .thenReturn(listOf(invite))
        `when`(calendarMemberRepository.findByCalendarIdAndUserId(calendar.id!!, userA.id!!))
            .thenReturn(existingActive)

        service.claimInvitesFor(userA)

        verify(calendarMemberRepository).delete(invite)
        verify(calendarMemberRepository, never()).save(invite)
    }

    @Test
    fun `accept invite by wrong user throws AccessDeniedException`() {
        val member = CalendarMember().apply {
            id = UUID.randomUUID()
            this.calendar = this@CalendarMemberServiceTest.calendar
            this.user = userA
            this.state = CalendarMemberState.INVITED
        }
        `when`(calendarMemberRepository.findById(member.id!!)).thenReturn(Optional.of(member))

        val error = assertThrows(AccessDeniedException::class.java) {
            service.acceptInvite(member.id!!, userB)
        }
        assertEquals("You do not have permission to accept this invitation.", error.message)
    }

    @Test
    fun `decline invite by wrong user throws AccessDeniedException`() {
        val member = CalendarMember().apply {
            id = UUID.randomUUID()
            this.calendar = this@CalendarMemberServiceTest.calendar
            this.user = userA
            this.state = CalendarMemberState.INVITED
        }
        `when`(calendarMemberRepository.findById(member.id!!)).thenReturn(Optional.of(member))

        val error = assertThrows(AccessDeniedException::class.java) {
            service.declineInvite(member.id!!, userB)
        }
        assertEquals("You do not have permission to decline this invitation.", error.message)
    }

    @Test
    fun `subscribing to a private calendar throws AccessDeniedException`() {
        calendar.visibility = Visibility.PRIVATE

        val error = assertThrows(AccessDeniedException::class.java) {
            service.subscribe(calendar.id!!, userA)
        }
        assertEquals("Cannot subscribe to a private calendar.", error.message)
    }

    @Test
    fun `subscribing to a public calendar creates active membership`() {
        calendar.visibility = Visibility.PUBLIC
        `when`(calendarMemberRepository.findByCalendarIdAndUserId(calendar.id!!, userA.id!!))
            .thenReturn(null)

        val member = service.subscribe(calendar.id!!, userA)

        assertEquals(CalendarMemberState.ACTIVE, member.state)
        assertEquals(CalendarMemberRole.VIEWER, member.role)
        assertEquals(userA, member.user)
    }

    @Test
    fun `unsubscribing from default calendar falls back to another enabled calendar`() {
        val otherCalendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Fallback Calendar"
            enabled = true
        }
        userA.defaultCalendar = calendar

        val member = CalendarMember().apply {
            id = UUID.randomUUID()
            this.calendar = this@CalendarMemberServiceTest.calendar
            this.user = userA
            this.state = CalendarMemberState.ACTIVE
        }
        `when`(calendarMemberRepository.findByCalendarIdAndUserId(calendar.id!!, userA.id!!))
            .thenReturn(member)
        `when`(trainingCalendarRepository.findAll(
            org.mockito.ArgumentMatchers.any<Specification<TrainingCalendar>>(),
            org.mockito.ArgumentMatchers.any<Sort>()
        )).thenReturn(listOf(otherCalendar))

        service.unsubscribe(calendar.id!!, userA)

        verify(calendarMemberRepository).delete(member)
        assertEquals(otherCalendar, userA.defaultCalendar)
        verify(appUserRepository).save(userA)
    }
}
