package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * The calendar predicate, against a real database.
 *
 * @SpringBootTest rather than @DataJpaTest: this project has no @DataJpaTest anywhere, and
 * database tests are wired with Testcontainers and @ServiceConnection — see
 * TrainingCalendarBootstrapIntegrationTest.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=spec-test@group.calendar.google.com"])
class TrainingEventSpecificationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private fun calendar(name: String, owner: AppUser) = trainingCalendarRepository.save(
        TrainingCalendar().apply {
            this.owner = owner
            displayName = name
            enabled = true
        }
    )

    private fun event(eventTitle: String, owner: AppUser, cal: TrainingCalendar) =
        trainingEventRepository.save(
            TrainingEvent().apply {
                title = eventTitle
                startTime = LocalDateTime.now().plusDays(1)
                endTime = LocalDateTime.now().plusDays(1).plusHours(1)
                createdBy = owner
                calendar = cal
            }
        )

    @Test
    fun `filters to one calendar and ignores a null calendar filter`() {
        val owner = appUserRepository.save(AppUser().apply {
            username = "spec-${UUID.randomUUID().toString().take(8)}"
            displayName = "Spec Tester"
            password = "x"
            role = Role.USER
        })
        val club = calendar("Club", owner)
        val home = calendar("Home", owner)
        val clubEvent = event("Club session", owner, club)
        val homeEvent = event("Home drill", owner, home)

        val scoped = trainingEventRepository.findAll(
            TrainingEventSpecification.withFilters(createdBy = owner, calendarId = club.id)
        )
        assertEquals(listOf(clubEvent.id), scoped.map { it.id })

        val unscoped = trainingEventRepository.findAll(
            TrainingEventSpecification.withFilters(createdBy = owner, calendarId = null)
        )
        assertEquals(setOf(clubEvent.id, homeEvent.id), unscoped.map { it.id }.toSet())
    }
}
