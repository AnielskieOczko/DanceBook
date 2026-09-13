package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
@TestPropertySource(properties = ["google.calendar.calendar-id=bootstrap-test@group.calendar.google.com"])
class TrainingCalendarBootstrapIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingCalendarService: TrainingCalendarService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    @Test
    fun `startup runner seeds default calendar and subsequent bootstrap backfills events without duplicating calendar`() {
        // The real CommandLineRunner ran at context start
        val calendars = trainingCalendarRepository.findAll()
        assertEquals(1, calendars.size)
        val defaultCal = calendars.first()
        assertEquals("bootstrap-test@group.calendar.google.com", defaultCal.googleCalendarId)
        assertTrue(defaultCal.isDefault)

        // Seed an event with null calendar
        val user = appUserRepository.save(AppUser().apply {
            username = "bt-${UUID.randomUUID().toString().take(8)}"
            displayName = "Bootstrap Tester"
            password = "x"
            role = Role.USER
        })
        val event = trainingEventRepository.save(TrainingEvent().apply {
            title = "Unassigned Event"
            startTime = LocalDateTime.now()
            endTime = LocalDateTime.now().plusHours(1)
            createdBy = user
            calendar = null
        })

        // Call bootstrapDefaultCalendar() again
        trainingCalendarService.bootstrapDefaultCalendar()

        // Still exactly one calendar
        assertEquals(1, trainingCalendarRepository.count())

        // The event was backfilled
        val reloaded = trainingEventRepository.findById(event.id!!).get()
        assertNotNull(reloaded.calendar)
        assertEquals(defaultCal.id, reloaded.calendar?.id)
    }
}
