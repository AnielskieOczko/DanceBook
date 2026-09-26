package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
class TrainingAttendancePerUserIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingCalendarService: TrainingCalendarService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventPersistence: TrainingEventPersistence
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var trainingStatsService: TrainingStatsService
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository.deleteAll()
        trainingEventRepository.deleteAll()
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

        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        trainingCalendarService.add(TrainingCalendarRequest(
            googleCalendarId = "cal-attendance@group.calendar.google.com",
            displayName = "Club Calendar"
        ))
    }

    @Test
    fun `two users on the same session record different outcomes, each seeing their own stats and history`() {
        // User A creates a past session
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        val event = trainingEventService.create(TrainingEventRequest(
            title = "Shared practice",
            date = LocalDate.now().minusDays(2),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            eventType = "TRAINING"
        ))

        // User B is also an attendee on this shared session
        val loaded = trainingEventRepository.findById(event.id!!).get()
        loaded.setAttendance(userB, AttendanceStatus.PLANNED)
        trainingEventRepository.save(loaded)

        // Initially unconfirmed for User A
        val eventForA = trainingEventService.findById(event.id!!)
        assertTrue(eventForA.isAwaitingConfirmationFor(userA))
        assertEquals(AttendanceStatus.PLANNED, eventForA.attendanceFor(userA))

        // User A confirms as ATTENDED
        trainingEventService.updateAttendance(event.id!!, AttendanceStatus.ATTENDED)

        val recordA = trainingRecordRepository.findByTrainingEventIdAndCreatedBy(event.id!!, userA)
        assertNotNull(recordA)
        assertEquals(TrainingOutcome.ATTENDED, recordA?.outcome)
        assertEquals(120, recordA?.durationMinutes)

        val statsA = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertEquals(1, statsA.counts.attended)
        assertEquals(0, statsA.counts.skipped)
        assertEquals(0, statsA.counts.unconfirmed)

        // Switch to User B: non-owner access to service.updateAttendance is forbidden
        `when`(appUserService.getCurrentUser()).thenReturn(userB)
        assertThrows(IllegalStateException::class.java) {
            trainingEventService.updateAttendance(event.id!!, AttendanceStatus.SKIPPED)
        }

        val recordBBefore = trainingRecordRepository.findByTrainingEventIdAndCreatedBy(event.id!!, userB)
        assertNull(recordBBefore)

        // Below access layer: User B records SKIPPED outcome via persistence bean
        val loadedForB = trainingEventRepository.findById(event.id!!).get()
        trainingEventPersistence.updateAttendance(loadedForB, userB, AttendanceStatus.SKIPPED)

        val recordB = trainingRecordRepository.findByTrainingEventIdAndCreatedBy(event.id!!, userB)
        assertNotNull(recordB)
        assertEquals(TrainingOutcome.SKIPPED, recordB?.outcome)

        val statsB = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertEquals(0, statsB.counts.attended)
        assertEquals(1, statsB.counts.skipped)
        assertEquals(0, statsB.counts.unconfirmed)

        // Switch back to User A: User A's stats and records are untouched
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        val statsAFinal = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertEquals(1, statsAFinal.counts.attended)
        assertEquals(0, statsAFinal.counts.skipped)
        assertEquals(0, statsAFinal.counts.unconfirmed)

        // Both records exist in repository, unique per (event, user)
        val allRecords = trainingRecordRepository.findAllByTrainingEventId(event.id!!)
        assertEquals(2, allRecords.size)
        assertTrue(allRecords.any { it.createdBy?.id == userA.id && it.outcome == TrainingOutcome.ATTENDED })
        assertTrue(allRecords.any { it.createdBy?.id == userB.id && it.outcome == TrainingOutcome.SKIPPED })

        // Deleting the session orphans both records and preserves both histories
        trainingEventService.delete(event.id!!)

        val orphanedRecords = trainingRecordRepository.findAllByTrainingEventId(event.id!!)
        assertEquals(2, orphanedRecords.size)
        assertTrue(orphanedRecords.all { it.orphanedAt != null })

        // Stats still reflect the orphaned records
        val statsAAfterDelete = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertEquals(1, statsAAfterDelete.counts.attended)

        `when`(appUserService.getCurrentUser()).thenReturn(userB)
        val statsBAfterDelete = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertEquals(1, statsBAfterDelete.counts.skipped)
    }
}
