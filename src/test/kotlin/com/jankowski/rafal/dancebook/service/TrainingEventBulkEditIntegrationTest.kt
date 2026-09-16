package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionSynchronizationManager
import jakarta.persistence.EntityManagerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A bulk edit writes each session to Google before the local rows are saved, so one session's
 * calendar call can fail while the rest succeed. This asserts what the app is left holding
 * afterwards: the failed session must be *unchanged locally*, not silently updated.
 *
 * The distinction only exists under an open session. `spring.jpa.open-in-view` defaults to
 * true and this app does not disable it, so the sessions loaded by a bulk edit are managed
 * for the whole request — and a mutation made before a failed calendar call would otherwise
 * be flushed by the transaction that saves the sessions that *did* succeed. The result would
 * be a row the app has changed and the calendar has not, which is exactly the divergence the
 * calendar integration exists to prevent.
 *
 * Deliberately NOT @Transactional, for the same reason as TrainingEventUpdateIntegrationTest:
 * the behaviour under test is what happens at flush time.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class TrainingEventBulkEditIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var owner: AppUser
    private lateinit var category: DanceCategory

    @BeforeEach
    fun setUp() {
        owner = appUserRepository.save(AppUser().apply {
            username = "bulk-tester-${UUID.randomUUID()}"
            displayName = "Bulk Tester"
            password = "x"
            role = Role.USER
        })
        category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Bulk Test Style ${UUID.randomUUID()}"
            predefined = false
        })
        `when`(appUserService.getCurrentUser()).thenReturn(owner)
        `when`(calendarClient.createEvent(any(""), any(TrainingEvent())))
            .thenAnswer { "google-${UUID.randomUUID()}" }
    }

    private fun <T> any(dummy: T): T {
        org.mockito.ArgumentMatchers.any<T>()
        return dummy
    }

    /** Same trick as [any], for eq: the matcher returns null, which Kotlin will not accept. */
    private fun eqArg(value: String): String {
        org.mockito.ArgumentMatchers.eq(value)
        return value
    }

    private fun request(title: String, segmentMinutes: List<Int> = listOf(60)) = TrainingEventRequest(
        title = title,
        date = LocalDate.of(2026, 3, 2),
        startTime = LocalTime.of(18, 0),
        endTime = LocalTime.of(20, 0),
        eventType = "TRAINING",
        attendanceStatus = "PLANNED",
        segments = segmentMinutes
            .map { TrainingEventSegmentRequest(categoryId = category.id, durationMinutes = it) }
            .toMutableList()
    )

    /** See TrainingEventUpdateIntegrationTest: binds an EntityManager the way OSIV does per request. */
    private fun <T> inOpenSession(block: () -> T): T {
        val em = entityManagerFactory.createEntityManager()
        TransactionSynchronizationManager.bindResource(entityManagerFactory, EntityManagerHolder(em))
        try {
            return block()
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory)
            em.close()
        }
    }

    /** Reads through a fresh EntityManager, so assertions see the database and not a cached entity. */
    private fun reload(id: UUID): TrainingEvent =
        trainingEventRepository.findAllByIdIn(listOf(id)).single()

    @Test
    fun `a session whose calendar update fails keeps its original event type`() {
        val ok = inOpenSession { trainingEventService.create(request("bulk ok")) }
        val failing = inOpenSession { trainingEventService.create(request("bulk fails")) }

        doThrow(RuntimeException("Google rejected the update"))
            .`when`(calendarClient)
            .updateEvent(any(""), eqArg(failing.googleEventId!!), any(TrainingEvent()))

        val result = inOpenSession {
            trainingEventService.bulkUpdateEventType(
                listOf(ok.id!!, failing.id!!),
                TrainingEventType.CAMP
            )
        }

        assertEquals(1, result.updatedCount, "only the session Google accepted counts as updated")
        assertEquals(1, result.failedCount, "the rejected session must be reported as failed")

        assertEquals(TrainingEventType.CAMP, reload(ok.id!!).eventType)
        assertEquals(
            TrainingEventType.TRAINING,
            reload(failing.id!!).eventType,
            "a session Google rejected must not be changed locally, or the app and the calendar diverge"
        )
    }

    @Test
    fun `a session whose calendar update fails keeps its original style breakdown`() {
        val ok = inOpenSession { trainingEventService.create(request("styles ok")) }
        val failing = inOpenSession { trainingEventService.create(request("styles fail")) }

        doThrow(RuntimeException("Google rejected the update"))
            .`when`(calendarClient)
            .updateEvent(any(""), eqArg(failing.googleEventId!!), any(TrainingEvent()))

        val replacement = mutableListOf(
            TrainingEventSegmentRequest(categoryId = category.id, durationMinutes = 30),
            TrainingEventSegmentRequest(categoryId = category.id, durationMinutes = 25)
        )

        val result = inOpenSession {
            trainingEventService.bulkUpdateSegments(listOf(ok.id!!, failing.id!!), replacement)
        }

        assertEquals(1, result.updatedCount)
        assertEquals(1, result.failedCount)

        assertEquals(
            listOf(30, 25),
            reload(ok.id!!).segments.sortedBy { it.sortOrder }.map { it.durationMinutes }
        )
        assertEquals(
            listOf(60),
            reload(failing.id!!).segments.sortedBy { it.sortOrder }.map { it.durationMinutes },
            "a rejected session must keep the breakdown the calendar still shows"
        )
    }
}
