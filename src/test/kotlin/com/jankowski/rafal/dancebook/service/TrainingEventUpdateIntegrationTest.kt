package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.SeriesScope
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
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
 * Reproduction for the duplicate-key failure on `unique_training_event_segment_sort_order`
 * seen when editing an existing session.
 *
 * Deliberately NOT @Transactional: the bug is about what happens at flush/commit, and a
 * test-managed rollback-only transaction would never commit the collection rebuild.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
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
    @Autowired private lateinit var trainingSeriesService: TrainingSeriesService

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
        // A fresh id per call, like the real API: thenReturn would hand every occurrence of a
        // series the same google_event_id and trip its unique constraint.
        `when`(calendarClient.createEvent(any(""), any(TrainingEvent()))).thenAnswer { "google-${UUID.randomUUID()}" }
    }

    private fun <T> any(dummy: T): T {
        org.mockito.ArgumentMatchers.any<T>()
        return dummy
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

    /**
     * The occurrences of the series [seed] belongs to, earliest first.
     *
     * Scoped to the series on purpose. This class is deliberately not @Transactional and
     * shares one container across its tests, so rows survive from one test to the next and
     * findAll() would count occurrences every earlier test left behind.
     */
    private fun occurrencesOf(seed: TrainingEvent): List<TrainingEvent> = inOpenSession {
        val series = reload(seed.id!!).series
            ?: error("training event ${seed.id} is not part of a series")
        trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)
    }

    @Test
    fun `applying an edit to every occurrence renames the whole series in place keeping IDs and Google event IDs`() {
        val seriesRequest = request("weekly practice").copy(
            repeat = "WEEKLY",
            // Four Mondays: 2, 9, 16 and 23 March 2026.
            repeatUntil = LocalDate.of(2026, 3, 23)
        )
        val first = inOpenSession { trainingSeriesService.create(seriesRequest) }
        val beforeOccurrences = occurrencesOf(first)
        assertEquals(4, beforeOccurrences.size)
        val beforeIds = beforeOccurrences.map { it.id }
        val beforeGoogleIds = beforeOccurrences.map { it.googleEventId }

        val edit = request("weekly practice renamed").copy(
            editScope = SeriesScope.THIS_AND_FOLLOWING,
            repeatUntil = LocalDate.of(2026, 3, 23)
        )
        inOpenSession { trainingSeriesService.updateThisAndFollowing(first.id!!, edit) }

        val afterOccurrences = occurrencesOf(first)
        assertEquals(4, afterOccurrences.size)
        assertEquals(beforeIds, afterOccurrences.map { it.id }, "occurrence IDs must not change")
        assertEquals(beforeGoogleIds, afterOccurrences.map { it.googleEventId }, "google event IDs must not change")
        val titles = afterOccurrences.map { it.title }
        assertEquals(0, titles.count { it == "weekly practice" }, "no occurrence keeps the old title")
        assertEquals(4, titles.count { it == "weekly practice renamed" }, "every occurrence is renamed in place")
    }

    @Test
    fun `all events edit updates past occurrences with recorded outcomes in place without orphaning records`() {
        val seriesRequest = request("spring series").copy(
            repeat = "WEEKLY",
            repeatUntil = LocalDate.of(2026, 3, 23)
        )
        val first = inOpenSession { trainingSeriesService.create(seriesRequest) }
        val occurrences = occurrencesOf(first)
        val firstOccurrence = occurrences[0]
        val thirdOccurrence = occurrences[2]
        val originalIds = occurrences.map { it.id }
        val originalGoogleIds = occurrences.map { it.googleEventId }

        // Confirm the first session as attended; this creates a training record
        inOpenSession {
            trainingEventService.updateAttendance(firstOccurrence.id!!, AttendanceStatus.ATTENDED)
        }
        val recordBefore = inOpenSession {
            trainingRecordRepository.findByTrainingEventId(firstOccurrence.id!!)!!
        }
        assertEquals("spring series", recordBefore.title)
        assertNull(recordBefore.orphanedAt)

        // Edit all events from the third occurrence
        val edit = request("spring series renamed", segmentMinutes = listOf(30, 45)).copy(
            editScope = SeriesScope.ALL_EVENTS,
            repeatUntil = LocalDate.of(2026, 3, 23)
        )
        inOpenSession { trainingSeriesService.updateAll(thirdOccurrence.id!!, edit) }

        val updatedOccurrences = occurrencesOf(first)
        assertEquals(4, updatedOccurrences.size)
        assertEquals(originalIds, updatedOccurrences.map { it.id }, "IDs must be preserved")
        assertEquals(originalGoogleIds, updatedOccurrences.map { it.googleEventId }, "Google event IDs must be preserved")
        assertTrue(updatedOccurrences.all { it.title == "spring series renamed" })
        assertEquals(AttendanceStatus.ATTENDED, reload(firstOccurrence.id!!).attendanceStatus, "attendance must be kept")

        // Check that the training record for the first occurrence was updated in place and NOT orphaned
        // Read inside one bound session, like the earlier record test: the breakdown is lazy,
        // so loading the record in one session and traversing it in the next cannot work.
        val (recordTitle, recordOrphanedAt, recordMinutes) = inOpenSession {
            val record = trainingRecordRepository.findByTrainingEventId(firstOccurrence.id!!)!!
            Triple(
                record.title,
                record.orphanedAt,
                record.segments.sortedBy { it.sortOrder }.map { it.durationMinutes }
            )
        }
        assertEquals("spring series renamed", recordTitle)
        assertNull(recordOrphanedAt, "training record must not be orphaned by series edit")
        assertEquals(listOf(30, 45), recordMinutes)
    }

    @Test
    fun `this event edit detaches occurrence so subsequent series-wide edit does not affect it`() {
        val seriesRequest = request("summer drill").copy(
            repeat = "WEEKLY",
            repeatUntil = LocalDate.of(2026, 3, 16) // 3 Mondays: 2, 9, 16
        )
        val created = inOpenSession { trainingSeriesService.create(seriesRequest) }
        val occurrences = occurrencesOf(created)
        assertEquals(3, occurrences.size)
        val secondOcc = occurrences[1]
        val secondOriginalId = secondOcc.id
        val secondOriginalGoogleId = secondOcc.googleEventId

        // Edit second occurrence with THIS_EVENT (detaching it)
        val detachEdit = request("detached one-off").copy(
            editScope = SeriesScope.THIS_EVENT,
            date = secondOcc.startTime.toLocalDate(),
            startTime = secondOcc.startTime.toLocalTime(),
            endTime = secondOcc.endTime.toLocalTime()
        )
        inOpenSession { trainingSeriesService.updateThisEvent(secondOcc.id!!, detachEdit) }

        val detachedReloaded = reload(secondOcc.id!!)
        assertNull(detachedReloaded.series, "detached session must have null series")
        assertEquals(secondOriginalId, detachedReloaded.id)
        assertEquals(secondOriginalGoogleId, detachedReloaded.googleEventId)
        assertEquals("detached one-off", detachedReloaded.title)

        // Subsequent series-wide edit on the first session
        val firstOcc = occurrences[0]
        val seriesEdit = request("summer drill updated").copy(
            editScope = SeriesScope.ALL_EVENTS,
            repeatUntil = LocalDate.of(2026, 3, 16)
        )
        inOpenSession { trainingSeriesService.updateAll(firstOcc.id!!, seriesEdit) }

        // The detached session must keep its one-off title!
        assertEquals("detached one-off", reload(secondOcc.id!!).title)
        assertEquals("summer drill updated", reload(firstOcc.id!!).title)
        assertEquals("summer drill updated", reload(occurrences[2].id!!).title)
    }
}
