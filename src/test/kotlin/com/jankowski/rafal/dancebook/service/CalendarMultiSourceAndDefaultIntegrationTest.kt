package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
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
class CalendarMultiSourceAndDefaultIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingCalendarService: TrainingCalendarService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var calendarSourceRepository: CalendarSourceRepository
    @Autowired private lateinit var trainingEventSourceRepository: TrainingEventSourceRepository
    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var calendarSyncService: CalendarSyncService
    @Autowired private lateinit var activeCalendarService: ActiveCalendarService
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var testUser: AppUser
    private lateinit var secondUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository.deleteAll()
        trainingEventRepository.deleteAll()
        appUserRepository.findAll().forEach {
            it.defaultCalendar = null
            appUserRepository.save(it)
        }
        trainingCalendarRepository.deleteAll()

        testUser = appUserRepository.save(AppUser().apply {
            username = "multi-user-${UUID.randomUUID()}"
            displayName = "Multi Source User"
            password = "pwd"
            role = Role.USER
        })

        secondUser = appUserRepository.save(AppUser().apply {
            username = "second-user-${UUID.randomUUID()}"
            displayName = "Second User"
            password = "pwd"
            role = Role.USER
        })

        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(calendarClient.verifyCalendar(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn("Verified")
    }

    @Test
    fun `calendar with two sources syncs events from both and deduplicates by iCalUID`() {
        val targetGoogleId = "target-source@group.calendar.google.com"
        val secondaryGoogleId = "secondary-source@group.calendar.google.com"

        // 1. Create a calendar with primary source (write target)
        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = targetGoogleId,
                displayName = "Multi-Source Dance Calendar"
            ),
            testUser
        )

        // 2. Add secondary source to the calendar
        val secondarySource = trainingCalendarService.addSource(
            cal.id!!,
            googleCalendarId = secondaryGoogleId,
            displayName = "Secondary Feed",
            isWriteTarget = false,
            actor = testUser
        )
        assertFalse(secondarySource.isWriteTarget)

        // Reload calendar and verify it has 2 sources, exactly 1 write target
        val reloadedCal = trainingCalendarService.findById(cal.id!!)!!
        assertEquals(2, reloadedCal.sources.size)
        assertEquals(1, reloadedCal.sources.count { it.isWriteTarget })
        assertEquals(targetGoogleId, reloadedCal.writeTarget?.googleCalendarId)

        // 3. Mock sync responses:
        // Source 1 returns Event 1 and Duplicate Event (with iCalUID = "common-uid-123")
        val duplicateIcalUid = "common-uid-123"
        val changeSet1 = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = "g-ev-1",
                    title = "Target Source Event",
                    start = LocalDateTime.of(2026, 10, 1, 10, 0),
                    end = LocalDateTime.of(2026, 10, 1, 11, 0),
                    description = "Notes 1",
                    iCalUid = "unique-uid-1"
                ),
                CalendarChange.Upserted(
                    googleEventId = "g-ev-shared-1",
                    title = "Shared Figure Workshop",
                    start = LocalDateTime.of(2026, 10, 2, 14, 0),
                    end = LocalDateTime.of(2026, 10, 2, 16, 0),
                    description = "Shared notes",
                    iCalUid = duplicateIcalUid
                )
            ),
            nextSyncToken = "token-target-1",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        // Source 2 returns Event 2 and Duplicate Event (with same iCalUID = "common-uid-123" but different googleEventId)
        val changeSet2 = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = "g-ev-2",
                    title = "Secondary Source Event",
                    start = LocalDateTime.of(2026, 10, 3, 18, 0),
                    end = LocalDateTime.of(2026, 10, 3, 19, 30),
                    description = "Notes 2",
                    iCalUid = "unique-uid-2"
                ),
                CalendarChange.Upserted(
                    googleEventId = "g-ev-shared-2",
                    title = "Shared Figure Workshop",
                    start = LocalDateTime.of(2026, 10, 2, 14, 0),
                    end = LocalDateTime.of(2026, 10, 2, 16, 0),
                    description = "Shared notes",
                    iCalUid = duplicateIcalUid
                )
            ),
            nextSyncToken = "token-secondary-1",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        `when`(calendarClient.listChanges(targetGoogleId, null)).thenReturn(changeSet1)
        `when`(calendarClient.listChanges(secondaryGoogleId, null)).thenReturn(changeSet2)

        // 4. Run sync
        val syncOutcome = calendarSyncService.syncCalendar(cal.id!!)
        assertTrue(syncOutcome.success)

        // 5. Verify events: exactly 3 events exist (not 4), because duplicate iCalUID was unified
        val events = trainingEventRepository.findAllByCalendarId(cal.id!!)
        assertEquals(3, events.size, "Duplicate event across sources with same iCalUID must appear only once")

        val sharedEvent = events.first { it.icalUid == duplicateIcalUid }
        assertEquals("Shared Figure Workshop", sharedEvent.title)

        // Verify training_event_source maps both Google event IDs for the shared event
        val sourcesForShared = trainingEventSourceRepository.findAllByTrainingEventId(sharedEvent.id!!)
        assertEquals(2, sourcesForShared.size, "Shared event must track mapping for both calendar sources")
        assertTrue(sourcesForShared.any { it.googleEventId == "g-ev-shared-1" })
        assertTrue(sourcesForShared.any { it.googleEventId == "g-ev-shared-2" })
    }

    @Test
    fun `new session created in DanceBook writes only to the write target and deletion mirrors`() {
        val targetGoogleId = "write-target@group.calendar.google.com"
        val secondaryGoogleId = "read-only-feed@group.calendar.google.com"

        val cal = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = targetGoogleId,
                displayName = "Practice Calendar"
            ),
            testUser
        )
        trainingCalendarService.addSource(
            cal.id!!,
            googleCalendarId = secondaryGoogleId,
            displayName = "Auxiliary Feed",
            isWriteTarget = false,
            actor = testUser
        )

        val createdGoogleEventId = "new-g-event-999"
        `when`(calendarClient.createEvent(eq(targetGoogleId), any(TrainingEvent()))).thenReturn(createdGoogleEventId)

        // Create new session in DanceBook
        val session = trainingEventService.create(
            TrainingEventRequest(
                title = "Waltz Technique Session",
                date = LocalDate.now(),
                startTime = LocalTime.of(15, 0),
                endTime = LocalTime.of(16, 0),
                calendarId = cal.id,
                eventType = TrainingEventType.TRAINING.name,
                attendanceStatus = AttendanceStatus.ATTENDED.name
            )
        )

        // Verify createEvent was called ONLY on the write target, NEVER on the secondary source
        verify(calendarClient).createEvent(eq(targetGoogleId), any(TrainingEvent()))
        verify(calendarClient, never()).createEvent(eq(secondaryGoogleId), any(TrainingEvent()))
        assertEquals(createdGoogleEventId, session.googleEventId)

        // Delete session in DanceBook
        trainingEventService.delete(session.id!!)

        // Verify deleteEvent was called on the write target
        verify(calendarClient).deleteEvent(eq(targetGoogleId), eq(createdGoogleEventId))
        verify(calendarClient, never()).deleteEvent(eq(secondaryGoogleId), any(""))
        assertTrue(trainingEventRepository.findById(session.id!!).isEmpty)
    }

    @Test
    fun `per-user defaults, new user empty state, and fallback on delete`() {
        // 1. Brand new user with no calendars
        `when`(appUserService.getCurrentUser()).thenReturn(secondUser)
        val selectableForNewUser = activeCalendarService.selectable()
        assertTrue(selectableForNewUser.isEmpty(), "Brand new user sees no calendars")
        assertNull(trainingCalendarService.findDefault(secondUser))

        // 2. User A creates their first calendar -> becomes their default automatically
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        val cal1 = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "cal1@group.calendar.google.com",
                displayName = "User A Cal 1"
            ),
            testUser
        )

        val refreshedUserA = appUserRepository.findById(testUser.id!!).get()
        assertEquals(cal1.id, refreshedUserA.defaultCalendar?.id, "First calendar becomes user's default")
        assertTrue(cal1.isDefaultFor(refreshedUserA))

        // 3. User A creates a second calendar -> first calendar remains default
        val cal2 = trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "cal2@group.calendar.google.com",
                displayName = "User A Cal 2"
            ),
            testUser
        )

        val userAAfterCal2 = appUserRepository.findById(testUser.id!!).get()
        assertEquals(cal1.id, userAAfterCal2.defaultCalendar?.id, "Creating second calendar does not change default")

        // 4. Second user's default remains unaffected
        val userBCheck = appUserRepository.findById(secondUser.id!!).get()
        assertNull(userBCheck.defaultCalendar, "Second user default is independent")

        // 5. Deleting default calendar while another exists falls back to another enabled calendar
        trainingCalendarService.delete(cal1.id!!, testUser)
        val userAAfterDeleteCal1 = appUserRepository.findById(testUser.id!!).get()
        assertEquals(cal2.id, userAAfterDeleteCal1.defaultCalendar?.id, "Cal2 becomes default after cal1 is deleted")

        // 6. User A deletes their last remaining calendar (cal2) -> default becomes null
        trainingCalendarService.delete(cal2.id!!, testUser)
        val userAAfterDeleteAll = appUserRepository.findById(testUser.id!!).get()
        assertNull(userAAfterDeleteAll.defaultCalendar, "Deleting last calendar clears default calendar")
    }

    private fun <T> eq(v: T): T {
        org.mockito.Mockito.eq(v)
        return v
    }

    private fun <T> any(dummy: T): T {
        org.mockito.ArgumentMatchers.any<T>()
        return dummy
    }
}
