package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions
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
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class TrainingCalendarDeleteIntegrationTest {

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
    @Autowired private lateinit var trainingStatsService: TrainingStatsService
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository.deleteAll()
        trainingEventRepository.deleteAll()
        trainingCalendarRepository.deleteAll()

        testUser = appUserRepository.save(AppUser().apply {
            username = "cal-del-${UUID.randomUUID()}"
            displayName = "Calendar Delete Tester"
            password = "pwd"
            role = Role.ADMIN
        })
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
    }

    @Test
    fun `calendar delete orphans session records without dropping stats and refuses default while another exists`() {
        // 1. Create default calendar A and second calendar B
        val calA = trainingCalendarService.add(
            TrainingCalendarRequest("cal-a-${UUID.randomUUID()}@group.calendar.google.com", "Primary Default Calendar")
        )
        val calB = trainingCalendarService.add(
            TrainingCalendarRequest("cal-b-${UUID.randomUUID()}@group.calendar.google.com", "Secondary Team Calendar")
        )
        assertTrue(calA.isDefault)

        // 2. Create an attended training session in calendar B
        val sessionRequest = TrainingEventRequest(
            title = "Team Drill Practice",
            date = LocalDate.now(),
            startTime = LocalTime.of(17, 0),
            endTime = LocalTime.of(18, 30),
            eventType = TrainingEventType.TRAINING.name,
            calendarId = calB.id,
            attendanceStatus = AttendanceStatus.ATTENDED.name
        )
        val savedSession = trainingEventService.create(sessionRequest)
        val sessionId = savedSession.id!!

        // Verify record is created and linked
        val recordBeforeDelete = trainingRecordRepository.findByTrainingEventId(sessionId)
        assertNotNull(recordBeforeDelete)
        assertNull(recordBeforeDelete!!.orphanedAt)
        assertEquals("Secondary Team Calendar", recordBeforeDelete.calendarName)

        val statsBefore = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertTrue(statsBefore.counts.attended >= 1)
        val minutesBefore = statsBefore.totalMinutesTrained

        // 3. Attempt to delete default calendar A while calendar B exists -> refused
        val refuseEx = assertThrows(IllegalStateException::class.java) {
            trainingCalendarService.delete(calA.id!!, testUser)
        }
        assertEquals("Make another calendar the default before deleting this one.", refuseEx.message)

        // 4. Delete calendar B
        clearInvocations(calendarClient)
        trainingCalendarService.delete(calB.id!!, testUser)

        // Verification: Google is never called for calendar delete
        verifyNoInteractions(calendarClient)

        // Calendar B is deleted
        assertNull(trainingCalendarService.findById(calB.id!!))

        // Session row in DanceBook is removed
        assertTrue(trainingEventRepository.findById(sessionId).isEmpty)

        // Training record is orphaned, not deleted
        val recordAfterDelete = trainingRecordRepository.findByTrainingEventId(sessionId)
        assertNotNull(recordAfterDelete)
        assertTrue(recordAfterDelete!!.isOrphaned)
        assertNotNull(recordAfterDelete.orphanedAt)
        assertEquals("Team Drill Practice", recordAfterDelete.title)
        assertEquals("Secondary Team Calendar", recordAfterDelete.calendarName)

        // Statistics totals have not dropped
        val statsAfter = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)
        assertEquals(statsBefore.counts.attended, statsAfter.counts.attended)
        assertEquals(minutesBefore, statsAfter.totalMinutesTrained)

        // 5. Deleting the last remaining calendar (calA) is permitted
        trainingCalendarService.delete(calA.id!!, testUser)
        assertNull(trainingCalendarService.findById(calA.id!!))
    }
}
