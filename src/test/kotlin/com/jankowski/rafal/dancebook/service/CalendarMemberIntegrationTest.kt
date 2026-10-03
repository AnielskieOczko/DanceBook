package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import com.jankowski.rafal.dancebook.security.CalendarInviteLoginListener
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class CalendarMemberIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var trainingCalendarService: TrainingCalendarService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var calendarMemberService: CalendarMemberService
    @Autowired private lateinit var calendarMemberRepository: CalendarMemberRepository
    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var activeCalendarService: ActiveCalendarService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var calendarInviteLoginListener: CalendarInviteLoginListener
    @Autowired private lateinit var activityEventRepository: ActivityEventRepository
    @Autowired private lateinit var trainingTimelineService: TrainingTimelineService
    @Autowired private lateinit var trainingStatsService: TrainingStatsService

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var ownerUser: AppUser
    private lateinit var memberUser: AppUser
    private lateinit var otherUser: AppUser

    @BeforeEach
    fun setUp() {
        activityEventRepository.deleteAll()
        trainingRecordRepository.deleteAll()
        trainingEventRepository.deleteAll()
        calendarMemberRepository.deleteAll()
        appUserRepository.findAll().forEach {
            it.defaultCalendar = null
            appUserRepository.save(it)
        }
        trainingCalendarRepository.deleteAll()

        ownerUser = appUserRepository.save(AppUser().apply {
            username = "owner-${UUID.randomUUID()}"
            displayName = "Calendar Owner"
            email = "owner-${UUID.randomUUID()}@example.com"
            password = "pwd"
            role = Role.USER
        })

        memberUser = appUserRepository.save(AppUser().apply {
            username = "member-${UUID.randomUUID()}"
            displayName = "Calendar Member"
            email = "member-${UUID.randomUUID()}@example.com"
            password = "pwd"
            role = Role.USER
        })

        otherUser = appUserRepository.save(AppUser().apply {
            username = "other-${UUID.randomUUID()}"
            displayName = "Other User"
            email = "other-${UUID.randomUUID()}@example.com"
            password = "pwd"
            role = Role.USER
        })
    }

    @Test
    fun `should invite user by username, keep pending until accepted, then activate`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "owner-cal@group.calendar.google.com",
                displayName = "Team Practice",
                visibility = Visibility.PRIVATE
            ),
            ownerUser
        )

        // Invite member by username
        val invitedMember = calendarMemberService.inviteMember(cal.id!!, memberUser.username, ownerUser)
        assertEquals(CalendarMemberState.INVITED, invitedMember.state)
        assertEquals(CalendarMemberRole.VIEWER, invitedMember.role)
        assertEquals(memberUser.id, invitedMember.user?.id)

        // Check before accepting: member should NOT see calendar in selectable
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        val selectableBefore = activeCalendarService.selectable()
        assertTrue(selectableBefore.none { it.id == cal.id })

        val pending = calendarMemberService.getPendingInvitesFor(memberUser)
        assertEquals(1, pending.size)
        assertEquals(cal.id, pending[0].calendar?.id)

        // Accept invitation
        val accepted = calendarMemberService.acceptInvite(invitedMember.id!!, memberUser)
        assertEquals(CalendarMemberState.ACTIVE, accepted.state)

        // After accepting: member sees calendar in selectable
        val selectableAfter = activeCalendarService.selectable()
        assertTrue(selectableAfter.any { it.id == cal.id })
    }

    @Test
    fun `should claim pending email invite on user login`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "owner-cal-2@group.calendar.google.com",
                displayName = "Studio Lessons",
                visibility = Visibility.PRIVATE
            ),
            ownerUser
        )

        val unregisteredEmail = "dancer-${UUID.randomUUID()}@example.com"
        calendarMemberService.inviteMember(cal.id!!, unregisteredEmail, ownerUser)

        val pendingInvite = calendarMemberRepository.findAllByUserIdIsNullAndInvitedEmailIgnoreCase(unregisteredEmail).firstOrNull()
        assertNotNull(pendingInvite)
        assertNull(pendingInvite!!.user)
        assertEquals(CalendarMemberState.INVITED, pendingInvite.state)

        // Now the user registers / is saved
        val newUser = appUserRepository.save(AppUser().apply {
            username = "newuser-${UUID.randomUUID()}"
            displayName = "New Dancer"
            email = unregisteredEmail
            password = "pwd"
            role = Role.USER
        })

        // Simulate login event
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(newUser)
        val principal = User(newUser.username, "pwd", listOf(SimpleGrantedAuthority("ROLE_USER")))
        val auth = UsernamePasswordAuthenticationToken(principal, "pwd", principal.authorities)
        calendarInviteLoginListener.onApplicationEvent(AuthenticationSuccessEvent(auth))

        // Invite should be claimed and linked to user (state remains INVITED until accepted)
        val claimed = calendarMemberRepository.findById(pendingInvite.id!!).get()
        assertNotNull(claimed.user)
        assertEquals(newUser.id, claimed.user?.id)
        assertEquals(CalendarMemberState.INVITED, claimed.state)

        // User sees it in pending invites and accepts it
        val pending = calendarMemberService.getPendingInvitesFor(newUser)
        assertEquals(1, pending.size)
        val accepted = calendarMemberService.acceptInvite(claimed.id!!, newUser)
        assertEquals(CalendarMemberState.ACTIVE, accepted.state)
    }

    @Test
    fun `should allow subscribing and unsubscribing to public calendar with history preserved and default fallback`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "public-cal@group.calendar.google.com",
                displayName = "Open Practice",
                visibility = Visibility.PUBLIC
            ),
            ownerUser
        )

        val session = trainingEventService.create(
            TrainingEventRequest(
                title = "Open Practice #1",
                date = LocalDate.now(),
                startTime = LocalTime.of(18, 0),
                endTime = LocalTime.of(20, 0),
                calendarId = cal.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        // Member subscribes
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        calendarMemberService.subscribe(cal.id!!, memberUser)

        // Member sees calendar and session
        assertTrue(activeCalendarService.selectable().any { it.id == cal.id })
        val sessionsForMember = trainingEventService.findInRange(
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().plusDays(1),
            cal.id
        )
        assertEquals(1, sessionsForMember.size)
        assertEquals(session.id, sessionsForMember[0].id)

        // Member records attendance
        trainingEventService.updateAttendance(session.id!!, AttendanceStatus.ATTENDED)
        val record = trainingRecordRepository.findByTrainingEventIdAndCreatedBy(session.id!!, memberUser)
        assertNotNull(record)
        assertEquals(TrainingOutcome.ATTENDED, record?.outcome)

        // Member sets it as default
        trainingCalendarService.setDefault(cal.id!!, memberUser)
        assertEquals(cal.id, appUserRepository.findById(memberUser.id!!).get().defaultCalendar?.id)

        // Member unsubscribes
        calendarMemberService.unsubscribe(cal.id!!, memberUser)

        // Calendar is no longer selectable for member
        assertFalse(activeCalendarService.selectable().any { it.id == cal.id })

        // Attendance history is retained!
        val preservedRecord = trainingRecordRepository.findByTrainingEventIdAndCreatedBy(session.id!!, memberUser)
        assertNotNull(preservedRecord)
        assertEquals(TrainingOutcome.ATTENDED, preservedRecord?.outcome)

        // Member's default calendar falls back (no longer points to cal)
        val reloadedMember = appUserRepository.findById(memberUser.id!!).get()
        assertNotEquals(cal.id, reloadedMember.defaultCalendar?.id)
    }

    @Test
    fun `should feed a followed calendar's sessions into the grid, timeline and stats under All calendars`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "followed-cal@group.calendar.google.com",
                displayName = "Followed Practice",
                visibility = Visibility.PUBLIC
            ),
            ownerUser
        )
        val session = trainingEventService.create(
            TrainingEventRequest(
                title = "Followed Practice #1",
                date = LocalDate.now().plusDays(1),
                startTime = LocalTime.of(18, 0),
                endTime = LocalTime.of(20, 0),
                calendarId = cal.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        // The session was created by the owner, so a creator-scoped query would never show it
        // to the subscriber. "All calendars" is every calendar the member owns or follows.
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        calendarMemberService.subscribe(cal.id!!, memberUser)

        val grid = trainingEventService.findInRange(LocalDateTime.now(), LocalDateTime.now().plusDays(3), null)
        assertTrue(grid.any { it.id == session.id }, "calendar grid under All calendars")

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0, calendarId = null)
        assertTrue(timeline.months.flatMap { it.entries }.any { it.event.id == session.id }, "timeline")

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME, null)
        assertEquals(1, stats.counts.upcoming, "upcoming sessions in stats and the dashboard")

        // Unsubscribing takes them out again.
        calendarMemberService.unsubscribe(cal.id!!, memberUser)
        val gridAfter = trainingEventService.findInRange(LocalDateTime.now(), LocalDateTime.now().plusDays(3), null)
        assertFalse(gridAfter.any { it.id == session.id })
    }

    @Test
    fun `should forbid member from editing, deleting, or rescheduling sessions (403)`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "shared-cal@group.calendar.google.com",
                displayName = "Class Sessions",
                visibility = Visibility.PUBLIC
            ),
            ownerUser
        )

        val session = trainingEventService.create(
            TrainingEventRequest(
                title = "Class #1",
                date = LocalDate.now(),
                startTime = LocalTime.of(19, 0),
                endTime = LocalTime.of(20, 0),
                calendarId = cal.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        // Member subscribes
        calendarMemberService.subscribe(cal.id!!, memberUser)
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)

        // Member attempts to update -> 403 AccessDeniedException
        assertThrows(AccessDeniedException::class.java) {
            trainingEventService.update(
                session.id!!,
                TrainingEventRequest(
                    title = "Hacked Title",
                    date = LocalDate.now(),
                    startTime = LocalTime.of(19, 0),
                    endTime = LocalTime.of(20, 0),
                    calendarId = cal.id,
                    eventType = TrainingEventType.TRAINING.name
                )
            )
        }

        // Member attempts to reschedule -> 403 AccessDeniedException
        assertThrows(AccessDeniedException::class.java) {
            trainingEventService.reschedule(
                session.id!!,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(1)
            )
        }

        // Member attempts to delete -> 403 AccessDeniedException
        assertThrows(AccessDeniedException::class.java) {
            trainingEventService.delete(session.id!!)
        }

        // Member attempts bulk operations -> skips non-owned sessions (0 modified/deleted)
        val bulkDelResult = trainingEventService.bulkDelete(listOf(session.id!!))
        assertEquals(0, bulkDelResult.deletedCount)
        assertTrue(trainingEventRepository.findById(session.id!!).isPresent)

        val bulkTypeResult = trainingEventService.bulkUpdateEventType(listOf(session.id!!), TrainingEventType.WORKSHOP)
        assertEquals(0, bulkTypeResult.updatedCount)
        assertEquals(TrainingEventType.TRAINING, trainingEventRepository.findById(session.id!!).get().eventType)
    }

    @Test
    fun `should retain subscribers when public calendar is made private and remove it from public list`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "workshop-cal@group.calendar.google.com",
                displayName = "Workshops",
                visibility = Visibility.PUBLIC
            ),
            ownerUser
        )

        // Member subscribes
        calendarMemberService.subscribe(cal.id!!, memberUser)

        // Verify in public list
        assertTrue(trainingCalendarService.findAllPublic().any { it.id == cal.id })

        // Owner makes it private
        trainingCalendarService.setVisibility(cal.id!!, Visibility.PRIVATE, ownerUser)

        // No longer in public list
        assertFalse(trainingCalendarService.findAllPublic().any { it.id == cal.id })

        // Member is still an active member and can see calendar
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        val visibleCal = trainingCalendarService.findByIdVisibleTo(cal.id!!, memberUser)
        assertNotNull(visibleCal)
        assertTrue(activeCalendarService.selectable().any { it.id == cal.id })
    }

    @Test
    fun `should revoke access with 404 when owner removes a member`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "private-cal@group.calendar.google.com",
                displayName = "Private Group",
                visibility = Visibility.PRIVATE
            ),
            ownerUser
        )

        val session = trainingEventService.create(
            TrainingEventRequest(
                title = "Private Group #1",
                date = LocalDate.now(),
                startTime = LocalTime.of(15, 0),
                endTime = LocalTime.of(16, 0),
                calendarId = cal.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        val member = calendarMemberService.inviteMember(cal.id!!, memberUser.username, ownerUser)
        calendarMemberService.acceptInvite(member.id!!, memberUser)

        // Member can see session
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        assertNotNull(trainingEventService.findById(session.id!!))

        // Owner removes member
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        calendarMemberService.removeMember(cal.id!!, member.id!!, ownerUser)

        // Now member gets 404
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        assertNull(trainingCalendarService.findByIdVisibleTo(cal.id!!, memberUser))
        assertThrows(EntityNotFoundException::class.java) {
            trainingEventService.findById(session.id!!)
        }
    }

    @Test
    fun `should not show disabled calendar where only another user has sessions in selector (#63)`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "cal-63@group.calendar.google.com",
                displayName = "Archive 63",
                visibility = Visibility.PUBLIC
            ),
            ownerUser
        )

        // Owner has a session on it
        trainingEventService.create(
            TrainingEventRequest(
                title = "Owner Session Only",
                date = LocalDate.now(),
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(11, 0),
                calendarId = cal.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        // Member user subscribes, but has NO sessions or attendance of their own on it
        calendarMemberService.subscribe(cal.id!!, memberUser)

        // Owner disables the calendar
        trainingCalendarService.setEnabled(cal.id!!, false)

        // Owner DOES see it in selectable because owner created a session on it
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val selectableForOwner = activeCalendarService.selectable()
        assertTrue(selectableForOwner.any { it.id == cal.id }, "Owner should see disabled calendar with their own sessions")

        // Member user does NOT see it in selectable because they have NO sessions of their own on it (#63)
        `when`(appUserService.getCurrentUser()).thenReturn(memberUser)
        val selectableForMember = activeCalendarService.selectable()
        assertFalse(selectableForMember.any { it.id == cal.id }, "User with no sessions should not see disabled calendar in selector (#63)")
    }

    @Test
    fun `should filter activity feed to calendars user belongs to`() {
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        val calMemberBelongsTo = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "feed-cal-1@group.calendar.google.com",
                displayName = "Feed Cal 1",
                visibility = Visibility.PRIVATE
            ),
            ownerUser
        )

        val calMemberDoesNotBelongTo = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "feed-cal-2@group.calendar.google.com",
                displayName = "Feed Cal 2",
                visibility = Visibility.PRIVATE
            ),
            ownerUser
        )

        // Add member to calMemberBelongsTo
        val m = calendarMemberService.inviteMember(calMemberBelongsTo.id!!, memberUser.username, ownerUser)
        calendarMemberService.acceptInvite(m.id!!, memberUser)

        // Create events on both
        val event1 = trainingEventService.create(
            TrainingEventRequest(
                title = "Event on Shared Cal",
                date = LocalDate.now(),
                startTime = LocalTime.of(12, 0),
                endTime = LocalTime.of(13, 0),
                calendarId = calMemberBelongsTo.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        val event2 = trainingEventService.create(
            TrainingEventRequest(
                title = "Event on Secret Cal",
                date = LocalDate.now(),
                startTime = LocalTime.of(14, 0),
                endTime = LocalTime.of(15, 0),
                calendarId = calMemberDoesNotBelongTo.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )

        val spec = ActivityEventSpecification.visibleTo(memberUser)
        val eventsForMember = activityEventRepository.findAll(spec)

        val targetIds = eventsForMember.mapNotNull { it.targetId }
        assertTrue(targetIds.contains(event1.id), "Member should see activity for calendar they belong to")
        assertFalse(targetIds.contains(event2.id), "Member should not see activity for calendar they do not belong to")
    }
}
