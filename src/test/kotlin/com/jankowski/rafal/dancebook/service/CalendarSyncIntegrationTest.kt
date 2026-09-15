package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
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
class CalendarSyncIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var calendarSyncService: CalendarSyncService
    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var testAdmin: AppUser
    private lateinit var defaultCal: TrainingCalendar
    private lateinit var category: DanceCategory

    @BeforeEach
    fun setUp() {
        testAdmin = appUserRepository.save(AppUser().apply {
            username = "sync-admin-${UUID.randomUUID()}"
            displayName = "Sync Administrator"
            password = "pwd"
            role = Role.ADMIN
        })

        category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Sync Style ${UUID.randomUUID()}"
            predefined = false
        })

        `when`(appUserService.getCurrentUser()).thenReturn(testAdmin)
        `when`(appUserService.getRootAdmin()).thenReturn(testAdmin)

        `when`(calendarClient.createEvent(any(""), any(TrainingEvent()))).thenAnswer { "google-${UUID.randomUUID()}" }

        defaultCal = trainingCalendarRepository.findByIsDefaultTrue()
            ?: trainingCalendarRepository.save(TrainingCalendar().apply {
                googleCalendarId = "default-cal-${UUID.randomUUID()}@group.calendar.google.com"
                displayName = "Default Test Calendar"
                isDefault = true
                enabled = true
            })
    }

    private fun <T> any(dummy: T): T {
        org.mockito.ArgumentMatchers.any<T>()
        return dummy
    }

    @Test
    fun `deleted session in Google removes training_event and leaves training_record orphaned and intact`() {
        // 1. Create an Attended session
        val createRequest = TrainingEventRequest(
            title = "Attended Workshop",
            date = LocalDate.of(2026, 9, 15),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            eventType = "TRAINING",
            attendanceStatus = "ATTENDED",
            calendarId = defaultCal.id,
            segments = mutableListOf(TrainingEventSegmentRequest(categoryId = category.id, durationMinutes = 120))
        )

        val created = trainingEventService.create(createRequest)
        val eventId = created.id!!
        val googleEventId = created.googleEventId!!
        assertNotNull(googleEventId)

        // Verify training record was created and is not orphaned
        val initialRecord = trainingRecordRepository.findByTrainingEventId(eventId)
        assertNotNull(initialRecord)
        assertEquals(TrainingOutcome.ATTENDED, initialRecord!!.outcome)
        assertEquals(120, initialRecord.durationMinutes)
        assertFalse(initialRecord.isOrphaned)

        // 2. Inbound sync returns a cancellation for this event
        val changeSet = CalendarChangeSet(
            changes = listOf(CalendarChange.Cancelled(googleEventId = googleEventId)),
            nextSyncToken = "sync-token-del",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(calendarClient.listChanges(defaultCal.googleCalendarId, defaultCal.syncToken)).thenReturn(changeSet)

        // 3. Run sync
        val report = calendarSyncService.syncAll()
        assertFalse(report.hasFailures)

        // 4. Assert: training_event is deleted, training_record is orphaned and intact
        val eventAfterSync = trainingEventRepository.findById(eventId)
        assertTrue(eventAfterSync.isEmpty, "training_event row must be deleted")

        val recordAfterSync = trainingRecordRepository.findByTrainingEventId(eventId)
        assertNotNull(recordAfterSync, "training_record must survive session deletion")
        assertTrue(recordAfterSync!!.isOrphaned, "training_record must be marked orphaned")
        assertNotNull(recordAfterSync.orphanedAt)
        assertEquals(120, recordAfterSync.durationMinutes, "duration must be preserved")
        assertEquals("Attended Workshop", recordAfterSync.title, "title must be preserved")
        assertEquals(TrainingOutcome.ATTENDED, recordAfterSync.outcome)
    }

    @Test
    fun `inbound time change on attended session updates training record occurredAt and duration`() {
        val createRequest = TrainingEventRequest(
            title = "Morning Practice",
            date = LocalDate.of(2026, 9, 16),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            eventType = "TRAINING",
            attendanceStatus = "ATTENDED",
            calendarId = defaultCal.id
        )

        val created = trainingEventService.create(createRequest)
        val eventId = created.id!!
        val googleEventId = created.googleEventId!!

        val initialRecord = trainingRecordRepository.findByTrainingEventId(eventId)
        assertNotNull(initialRecord)
        assertEquals(60, initialRecord!!.durationMinutes)

        // Google Calendar updates time to 09:00 - 11:00 (120 minutes)
        val newStart = LocalDateTime.of(2026, 9, 16, 9, 0)
        val newEnd = LocalDateTime.of(2026, 9, 16, 11, 0)
        val changeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = googleEventId,
                    title = "Morning Practice (Extended)",
                    start = newStart,
                    end = newEnd,
                    description = null
                )
            ),
            nextSyncToken = "sync-token-time",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(calendarClient.listChanges(defaultCal.googleCalendarId, defaultCal.syncToken)).thenReturn(changeSet)

        val report = calendarSyncService.syncAll()
        assertFalse(report.hasFailures)

        val updatedRecord = trainingRecordRepository.findByTrainingEventId(eventId)
        assertNotNull(updatedRecord)
        assertEquals(120, updatedRecord!!.durationMinutes)
        assertEquals(newStart, updatedRecord.occurredAt)
        assertEquals("Morning Practice (Extended)", updatedRecord.title)
    }

    @Test
    fun `adding second calendar and syncing adopts its events leaving first calendar untouched`() {
        val cal2 = trainingCalendarRepository.save(TrainingCalendar().apply {
            googleCalendarId = "cal2-${UUID.randomUUID()}@group.calendar.google.com"
            displayName = "Second Calendar"
            isDefault = false
            enabled = true
        })

        val cal1ChangeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "cal1-next-token",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(calendarClient.listChanges(defaultCal.googleCalendarId, defaultCal.syncToken)).thenReturn(cal1ChangeSet)

        val cal2GoogleId = "cal2-google-event-${UUID.randomUUID()}"
        val cal2Start = LocalDateTime.of(2026, 9, 20, 14, 0)
        val cal2End = LocalDateTime.of(2026, 9, 20, 16, 0)
        val cal2ChangeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = cal2GoogleId,
                    title = "External Workshop",
                    start = cal2Start,
                    end = cal2End,
                    description = "External notes"
                )
            ),
            nextSyncToken = "cal2-next-token",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(calendarClient.listChanges(cal2.googleCalendarId, cal2.syncToken)).thenReturn(cal2ChangeSet)

        val report = calendarSyncService.syncAll()
        assertFalse(report.hasFailures)

        val adoptedEvent = trainingEventRepository.findByGoogleEventId(cal2GoogleId).orElse(null)
        assertNotNull(adoptedEvent)
        assertEquals("External Workshop", adoptedEvent!!.title)
        assertEquals(cal2.id, adoptedEvent.calendar?.id)
        assertEquals(testAdmin.id, adoptedEvent.createdBy?.id)
    }
}
