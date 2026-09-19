package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=reconcile-test@group.calendar.google.com"])
class TrainingRecordReconciliationIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingCalendarBootstrap: TrainingCalendarBootstrap
    @Autowired private lateinit var trainingRecordReconciliationService: TrainingRecordReconciliationService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private fun createUser(prefix: String): AppUser =
        appUserRepository.save(AppUser().apply {
            username = "$prefix-${UUID.randomUUID().toString().take(8)}"
            displayName = "Reconciliation Tester"
            password = "x"
            role = Role.USER
        })

    private fun createRecord(
        eventId: UUID,
        user: AppUser,
        calId: UUID? = null,
        calName: String? = null,
        orphaned: LocalDateTime? = null
    ): TrainingRecord =
        trainingRecordRepository.save(TrainingRecord().apply {
            trainingEventId = eventId
            occurredAt = LocalDateTime.now()
            durationMinutes = 60
            outcome = TrainingOutcome.ATTENDED
            title = "Reconcile Test Session"
            eventType = TrainingEventType.TRAINING
            calendarId = calId
            calendarName = calName
            createdBy = user
            orphanedAt = orphaned
        })

    @Test
    fun `single startup boot heals restore scenario where events and records started without calendars`() {
        val user = createUser("restore")

        // Seed pre-existing event with null calendar (as after pre-#52 restore)
        val event = trainingEventRepository.save(TrainingEvent().apply {
            title = "Restored Event"
            startTime = LocalDateTime.now()
            endTime = LocalDateTime.now().plusHours(1)
            createdBy = user
            calendar = null
        })

        // Seed record with null calendar
        val record = createRecord(event.id!!, user, calId = null, calName = null)

        // Run the startup runner (simulating a single boot where event bootstrap + record reconciliation run)
        trainingCalendarBootstrap.run()

        // Verify the event got a calendar
        val reloadedEvent = trainingEventRepository.findById(event.id!!).get()
        assertNotNull(reloadedEvent.calendar)
        val assignedCalId = reloadedEvent.calendar!!.id!!
        val assignedCal = trainingCalendarRepository.findById(assignedCalId).get()

        // Verify the record was reconciled within the same boot
        val reloadedRecord = trainingRecordRepository.findById(record.id!!).get()
        assertEquals(assignedCal.id, reloadedRecord.calendarId)
        assertEquals(assignedCal.displayName, reloadedRecord.calendarName)

        // Second pass repairs nothing
        val secondPassCount = trainingRecordReconciliationService.reconcile()
        assertEquals(0, secondPassCount)
    }

    @Test
    fun `leaves orphaned records and records with deleted sessions with null calendar`() {
        val user = createUser("orphaned")

        // A record whose session has been deleted
        val deletedSessionId = UUID.randomUUID()
        val deletedSessionRecord = createRecord(deletedSessionId, user, calId = null, calName = null)

        // An explicitly orphaned record
        val orphanedRecord = createRecord(
            UUID.randomUUID(),
            user,
            calId = null,
            calName = null,
            orphaned = LocalDateTime.now()
        )

        val repaired = trainingRecordReconciliationService.reconcile()
        assertEquals(0, repaired)

        val reloadedDeleted = trainingRecordRepository.findById(deletedSessionRecord.id!!).get()
        assertNull(reloadedDeleted.calendarId)
        assertNull(reloadedDeleted.calendarName)

        val reloadedOrphan = trainingRecordRepository.findById(orphanedRecord.id!!).get()
        assertNull(reloadedOrphan.calendarId)
        assertNull(reloadedOrphan.calendarName)
    }

    @Test
    fun `leaves record alone when session exists but has no calendar`() {
        val user = createUser("nocal")

        val event = trainingEventRepository.save(TrainingEvent().apply {
            title = "No Calendar Event"
            startTime = LocalDateTime.now()
            endTime = LocalDateTime.now().plusHours(1)
            createdBy = user
            calendar = null
        })

        val record = createRecord(event.id!!, user, calId = null, calName = null)

        val repaired = trainingRecordReconciliationService.reconcile()
        assertEquals(0, repaired)

        val reloaded = trainingRecordRepository.findById(record.id!!).get()
        assertNull(reloaded.calendarId)
        assertNull(reloaded.calendarName)
    }

    @Test
    fun `does not touch records that already have a calendar or overwrite their snapshot name`() {
        val user = createUser("existing")

        val cal = trainingCalendarRepository.save(TrainingCalendar().apply {
            googleCalendarId = "custom-${UUID.randomUUID().toString().take(8)}@group.calendar.google.com"
            displayName = "Custom Calendar"
            isDefault = false
            enabled = true
        })

        val event = trainingEventRepository.save(TrainingEvent().apply {
            title = "Custom Event"
            startTime = LocalDateTime.now()
            endTime = LocalDateTime.now().plusHours(1)
            createdBy = user
            calendar = cal
        })

        // Record with deliberate historical snapshot name
        val originalSnapshotName = "Point In Time Name"
        val record = createRecord(event.id!!, user, calId = cal.id, calName = originalSnapshotName)

        // Reconcile
        val repaired = trainingRecordReconciliationService.reconcile()
        assertEquals(0, repaired)

        val reloaded = trainingRecordRepository.findById(record.id!!).get()
        assertEquals(cal.id, reloaded.calendarId)
        assertEquals(originalSnapshotName, reloaded.calendarName, "deliberate point-in-time name must not be overwritten")
    }

    @Test
    fun `reconciliation runs and repairs without depending on a default calendar`() {
        val user = createUser("nodefault")

        // A non-default calendar
        val secondaryCal = trainingCalendarRepository.save(TrainingCalendar().apply {
            googleCalendarId = "secondary-${UUID.randomUUID().toString().take(8)}@group.calendar.google.com"
            displayName = "Secondary Practice"
            isDefault = false
            enabled = true
        })

        val event = trainingEventRepository.save(TrainingEvent().apply {
            title = "Secondary Event"
            startTime = LocalDateTime.now()
            endTime = LocalDateTime.now().plusHours(1)
            createdBy = user
            calendar = secondaryCal
        })

        val record = createRecord(event.id!!, user, calId = null, calName = null)

        val repaired = trainingRecordReconciliationService.reconcile()
        assertEquals(1, repaired)

        val reloaded = trainingRecordRepository.findById(record.id!!).get()
        assertEquals(secondaryCal.id, reloaded.calendarId)
        assertEquals("Secondary Practice", reloaded.calendarName)
    }
}
