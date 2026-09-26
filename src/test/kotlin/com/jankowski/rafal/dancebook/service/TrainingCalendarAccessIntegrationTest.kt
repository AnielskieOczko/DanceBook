package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
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
class TrainingCalendarAccessIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingCalendarService: TrainingCalendarService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var activeCalendarService: ActiveCalendarService
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var adminUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository.deleteAll()
        trainingEventRepository.deleteAll()
        appUserRepository.findAll().forEach {
            it.defaultCalendar = null
            appUserRepository.save(it)
        }
        trainingCalendarRepository.deleteAll()

        userA = appUserRepository.save(AppUser().apply {
            username = "user-a-${UUID.randomUUID()}"
            displayName = "User A"
            password = "pwd"
            role = Role.USER
        })

        userB = appUserRepository.save(AppUser().apply {
            username = "user-b-${UUID.randomUUID()}"
            displayName = "User B"
            password = "pwd"
            role = Role.USER
        })

        adminUser = appUserRepository.save(AppUser().apply {
            username = "admin-${UUID.randomUUID()}"
            displayName = "Admin User"
            password = "pwd"
            role = Role.ADMIN
        })
    }

    @Test
    fun `User B cannot see, select, or read sessions from User A's private calendar (404), but can after it is made PUBLIC`() {
        // User A creates a PRIVATE calendar
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        val calA = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "user-a-cal@group.calendar.google.com",
                displayName = "User A Private Calendar",
                visibility = Visibility.PRIVATE
            ),
            userA
        )

        // User A creates a session on this calendar
        val sessionA = trainingEventService.create(
            TrainingEventRequest(
                title = "Private Session A",
                date = LocalDate.now(),
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(11, 0),
                calendarId = calA.id,
                eventType = TrainingEventType.TRAINING.name,
                attendanceStatus = AttendanceStatus.ATTENDED.name
            )
        )
        val sessionId = sessionA.id!!

        // Switch to User B
        `when`(appUserService.getCurrentUser()).thenReturn(userB)

        // 1. User B cannot see User A's private calendar in selectable list
        val selectableForB = activeCalendarService.selectable()
        assertTrue(selectableForB.none { it.id == calA.id }, "User B must not see User A's private calendar in selectable calendars")

        // 2. User B cannot read sessions from User A's private calendar by id -> EntityNotFoundException (404)
        assertThrows(EntityNotFoundException::class.java) {
            trainingEventService.findById(sessionId)
        }

        // 3. User B cannot read sessions via findInRange
        val rangeSessionsForB = trainingEventService.findInRange(
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().plusDays(1),
            calA.id
        )
        assertTrue(rangeSessionsForB.isEmpty(), "User B must not see events in range query for User A's private calendar")

        // 4. User B cannot modify or delete User A's calendar -> AccessDeniedException
        assertThrows(AccessDeniedException::class.java) {
            trainingCalendarService.update(
                calA.id!!,
                TrainingCalendarRequest(
                    googleCalendarId = "user-a-cal@group.calendar.google.com",
                    displayName = "Hacked Name"
                ),
                userB
            )
        }
        assertThrows(AccessDeniedException::class.java) {
            trainingCalendarService.delete(calA.id!!, userB)
        }

        // 5. User A makes the calendar PUBLIC
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        trainingCalendarService.setVisibility(calA.id!!, Visibility.PUBLIC, userA)

        // 6. User B now CAN see, select, and read sessions
        `when`(appUserService.getCurrentUser()).thenReturn(userB)
        val selectableForBNow = activeCalendarService.selectable()
        assertTrue(selectableForBNow.any { it.id == calA.id }, "User B must see PUBLIC calendar in selectable list")

        val sessionReadByB = trainingEventService.findById(sessionId)
        assertNotNull(sessionReadByB)
        assertEquals("Private Session A", sessionReadByB.title)

        val rangeSessionsNow = trainingEventService.findInRange(
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().plusDays(1),
            calA.id
        )
        assertEquals(1, rangeSessionsNow.size)
        assertEquals(sessionId, rangeSessionsNow[0].id)
    }

    @Test
    fun `Admin user can see and manage private calendars of other users`() {
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        val calA = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "admin-test-cal@group.calendar.google.com",
                displayName = "User A Secret",
                visibility = Visibility.PRIVATE
            ),
            userA
        )

        // Switch to Admin
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)

        val selectableForAdmin = activeCalendarService.selectable()
        assertTrue(selectableForAdmin.any { it.id == calA.id }, "Admin can see any calendar in selectable list")

        // Admin can update calendar
        val updated = trainingCalendarService.update(
            calA.id!!,
            TrainingCalendarRequest(
                googleCalendarId = "admin-test-cal@group.calendar.google.com",
                displayName = "Admin Renamed Calendar"
            ),
            adminUser
        )
        assertEquals("Admin Renamed Calendar", updated.displayName)
    }

    @Test
    fun `User B creating a session with User A's private calendar id gets 404 and creates no session in DanceBook or Google`() {
        // User A creates a PRIVATE calendar
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        val calA = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "user-a-target@group.calendar.google.com",
                displayName = "User A Private Target",
                visibility = Visibility.PRIVATE
            ),
            userA
        )

        // Switch to User B
        `when`(appUserService.getCurrentUser()).thenReturn(userB)

        // 1. User B tries to create a session on User A's private calendar -> EntityNotFoundException (404)
        assertThrows(EntityNotFoundException::class.java) {
            trainingEventService.create(
                TrainingEventRequest(
                    title = "Unauthorized Session",
                    date = LocalDate.now(),
                    startTime = LocalTime.of(14, 0),
                    endTime = LocalTime.of(15, 0),
                    calendarId = calA.id,
                    eventType = TrainingEventType.TRAINING.name
                )
            )
        }

        // Verify no event was created in Google or local DB
        org.mockito.Mockito.verify(calendarClient, org.mockito.Mockito.never())
            .createEvent(eq("user-a-target@group.calendar.google.com"), any(TrainingEvent::class.java))
        assertTrue(trainingEventRepository.findAll().isEmpty(), "No session should be saved in DB")

        // 2. User B creates their own private calendar
        val calB = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "user-b-cal@group.calendar.google.com",
                displayName = "User B Private Calendar",
                visibility = Visibility.PRIVATE
            ),
            userB
        )

        // User B creates a session on their own calendar explicitly
        `when`(calendarClient.createEvent(
            eq("user-b-cal@group.calendar.google.com"),
            any(TrainingEvent::class.java)
        )).thenAnswer { "google-b-session-" + UUID.randomUUID() }

        val sessionBExplicit = trainingEventService.create(
            TrainingEventRequest(
                title = "User B Own Session",
                date = LocalDate.now(),
                startTime = LocalTime.of(16, 0),
                endTime = LocalTime.of(17, 0),
                calendarId = calB.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )
        assertNotNull(sessionBExplicit.id)
        assertEquals(calB.id, sessionBExplicit.calendar?.id)
        assertNotNull(sessionBExplicit.googleEventId)

        // 3. User B creates a session on their default calendar (calendarId = null)
        val sessionBDefault = trainingEventService.create(
            TrainingEventRequest(
                title = "User B Default Session",
                date = LocalDate.now(),
                startTime = LocalTime.of(18, 0),
                endTime = LocalTime.of(19, 0),
                calendarId = null,
                eventType = TrainingEventType.TRAINING.name
            )
        )
        assertNotNull(sessionBDefault.id)
        assertEquals(calB.id, sessionBDefault.calendar?.id)

        // 4. User A makes calA PUBLIC -> now User B CAN create a session targeting calA
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        trainingCalendarService.setVisibility(calA.id!!, Visibility.PUBLIC, userA)

        `when`(appUserService.getCurrentUser()).thenReturn(userB)
        `when`(calendarClient.createEvent(
            eq("user-a-target@group.calendar.google.com"),
            any(TrainingEvent::class.java)
        )).thenAnswer { "google-pub-session-" + UUID.randomUUID() }

        val sessionOnPublicCal = trainingEventService.create(
            TrainingEventRequest(
                title = "User B on Public Cal A",
                date = LocalDate.now(),
                startTime = LocalTime.of(20, 0),
                endTime = LocalTime.of(21, 0),
                calendarId = calA.id,
                eventType = TrainingEventType.TRAINING.name
            )
        )
        assertNotNull(sessionOnPublicCal.id)
        assertEquals(calA.id, sessionOnPublicCal.calendar?.id)
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)
    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value
}
