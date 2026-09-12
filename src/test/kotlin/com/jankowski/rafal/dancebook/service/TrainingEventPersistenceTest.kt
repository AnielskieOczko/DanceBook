package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.util.UUID

/**
 * The record has to be written by the same transaction as the session, which is what this
 * bean is. TrainingEventServiceTest mocks this bean out entirely, so without these the
 * wiring would be untested.
 */
class TrainingEventPersistenceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingRecordWriter: TrainingRecordWriter
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var persistence: TrainingEventPersistence
    private lateinit var actor: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingRecordWriter = mock(TrainingRecordWriter::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        persistence = TrainingEventPersistence(trainingEventRepository, trainingRecordWriter, eventPublisher)
        actor = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
    }

    private fun event(status: AttendanceStatus = AttendanceStatus.ATTENDED): TrainingEvent {
        val start = LocalDateTime.of(2026, 9, 7, 18, 0)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
            startTime = start
            endTime = start.plusMinutes(90)
            attendanceStatus = status
            createdBy = actor
        }
    }

    @Test
    fun `inserting a session syncs its record`() {
        val toSave = event()
        `when`(trainingEventRepository.save(toSave)).thenReturn(toSave)

        persistence.insert(toSave, actor)

        verify(trainingRecordWriter).sync(toSave)
    }

    @Test
    fun `updating a session syncs its record`() {
        val toSave = event(AttendanceStatus.SKIPPED)
        `when`(trainingEventRepository.save(toSave)).thenReturn(toSave)

        persistence.applyUpdate(toSave, actor)

        verify(trainingRecordWriter).sync(toSave)
    }

    @Test
    fun `deleting a session orphans its record before the row goes`() {
        val toDelete = event()

        persistence.remove(toDelete, actor)

        // Order matters only for readability -- there is no foreign key between the two --
        // but reading the orphan first keeps the intent obvious.
        val order: InOrder = inOrder(trainingRecordWriter, trainingEventRepository)
        order.verify(trainingRecordWriter).orphan(listOf(toDelete.id!!))
        order.verify(trainingEventRepository).delete(toDelete)
    }
}
