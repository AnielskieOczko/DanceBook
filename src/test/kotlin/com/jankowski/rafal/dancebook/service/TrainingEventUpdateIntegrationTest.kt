package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import jakarta.persistence.EntityManagerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Reproduction for the duplicate-key failure on `unique_training_event_segment_sort_order`
 * seen when editing an existing session.
 *
 * Deliberately NOT @Transactional: the bug is about what happens at flush/commit, and a
 * test-managed rollback-only transaction would never commit the collection rebuild.
 */
@SpringBootTest
@Testcontainers
class TrainingEventUpdateIntegrationTest {

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
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var owner: AppUser
    private lateinit var category: DanceCategory

    @BeforeEach
    fun setUp() {
        owner = appUserRepository.save(AppUser().apply {
            username = "edit-tester-${UUID.randomUUID()}"
            displayName = "Edit Tester"
            password = "x"
            role = Role.USER
        })
        category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Edit Test Style ${UUID.randomUUID()}"
            predefined = false
        })
        `when`(appUserService.getCurrentUser()).thenReturn(owner)
        `when`(calendarClient.createEvent(anyNotNull())).thenReturn("google-${UUID.randomUUID()}")
    }

    /**
     * Mockito's matchers return null, which Kotlin rejects for a non-null parameter. Declaring
     * the return as a generic `T` sidesteps the call-site null check the platform type triggers.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNotNull(): T {
        any<T>()
        return null as T
    }

    private fun request(
        title: String,
        attendance: String = "PLANNED",
        segmentMinutes: List<Int> = listOf(60)
    ) = TrainingEventRequest(
        title = title,
        date = LocalDate.of(2026, 3, 2),
        startTime = LocalTime.of(18, 0),
        endTime = LocalTime.of(20, 0),
        eventType = "TRAINING",
        attendanceStatus = attendance,
        segments = segmentMinutes
            .map { TrainingEventSegmentRequest(categoryId = category.id, durationMinutes = it) }
            .toMutableList()
    )

    /**
     * Runs [block] with an EntityManager bound to the thread, exactly as
     * OpenEntityManagerInViewInterceptor does for every web request (spring.jpa.open-in-view
     * defaults to true and this app does not disable it).
     *
     * Load-bearing, not ceremony: TrainingEventServiceImpl.update is deliberately not
     * @Transactional, so without a bound session `event.segments.clear()` throws
     * LazyInitializationException. The production behaviour under test only exists inside
     * this binding.
     */
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

    @Test
    fun `editing a session's title keeps exactly one segment`() {
        val created = inOpenSession { trainingEventService.create(request("new training")) }

        inOpenSession { trainingEventService.update(created.id!!, request("new training edited")) }

        val reloaded = trainingEventRepository.findAllByIdIn(listOf(created.id!!)).single()
        assertEquals("new training edited", reloaded.title)
        assertEquals(1, reloaded.segments.size, "the rebuilt breakdown must not duplicate rows")
        assertEquals(60, reloaded.segments.single().durationMinutes)
    }

    @Test
    fun `a session's breakdown can grow and shrink across edits`() {
        val created = inOpenSession { trainingEventService.create(request("grower")) }

        inOpenSession {
            trainingEventService.update(created.id!!, request("grower", segmentMinutes = listOf(30, 30, 40)))
        }
        assertEquals(
            listOf(30, 30, 40),
            reload(created.id!!).segments.sortedBy { it.sortOrder }.map { it.durationMinutes }
        )

        inOpenSession {
            trainingEventService.update(created.id!!, request("grower", segmentMinutes = listOf(45)))
        }
        assertEquals(
            listOf(45),
            reload(created.id!!).segments.sortedBy { it.sortOrder }.map { it.durationMinutes }
        )
    }

    @Test
    fun `editing a confirmed session rebuilds its training record breakdown`() {
        val created = inOpenSession {
            trainingEventService.create(request("attended", attendance = "ATTENDED"))
        }

        // The record already exists with one segment; this rewrites it, which is where the
        // record side would hit the same unique-constraint collision.
        inOpenSession {
            trainingEventService.update(
                created.id!!,
                request("attended edited", attendance = "ATTENDED", segmentMinutes = listOf(20, 25))
            )
        }

        // Read inside a bound session: the record's breakdown is lazy, like the event's.
        val (title, minutes) = inOpenSession {
            val record = trainingRecordRepository.findByTrainingEventId(created.id!!)!!
            record.title to record.segments.sortedBy { it.sortOrder }.map { it.durationMinutes }
        }
        assertEquals("attended edited", title)
        assertEquals(listOf(20, 25), minutes)
    }

    private fun reload(id: UUID) = trainingEventRepository.findAllByIdIn(listOf(id)).single()
}
