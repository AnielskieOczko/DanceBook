package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingBulkAttendanceUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingBulkDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingBulkUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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

    @Test
    fun `bulk updating sessions syncs their records and publishes a single bulk event`() {
        val event1 = event(AttendanceStatus.PLANNED)
        val event2 = event(AttendanceStatus.PLANNED)
        val events = listOf(event1, event2)
        `when`(trainingEventRepository.saveAll(events)).thenReturn(events)

        val updated = persistence.bulkUpdateAttendance(events, AttendanceStatus.ATTENDED, actor)

        assertEquals(2, updated.size)
        assertEquals(AttendanceStatus.ATTENDED, event1.attendanceStatus)
        assertEquals(AttendanceStatus.ATTENDED, event2.attendanceStatus)
        verify(trainingRecordWriter).sync(event1)
        verify(trainingRecordWriter).sync(event2)

        val captor = ArgumentCaptor.forClass(TrainingBulkAttendanceUpdatedEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals(2, captor.value.count)
        assertEquals(AttendanceStatus.ATTENDED, captor.value.status)
        assertEquals(actor, captor.value.actor)
    }

    @Test
    fun `bulk updating empty list does nothing`() {
        val updatedAttendance = persistence.bulkUpdateAttendance(emptyList(), AttendanceStatus.ATTENDED, actor)
        val updatedSessions = persistence.bulkUpdate(emptyList(), "event type", actor)

        assertTrue(updatedAttendance.isEmpty())
        assertTrue(updatedSessions.isEmpty())
        verifyNoInteractions(trainingRecordWriter)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `bulk removing sessions orphans their records, deletes rows, and publishes a single bulk event`() {
        val event1 = event()
        val event2 = event()
        val events = listOf(event1, event2)

        val removedCount = persistence.bulkRemove(events, actor)

        assertEquals(2, removedCount)
        val order: InOrder = inOrder(trainingRecordWriter, trainingEventRepository)
        order.verify(trainingRecordWriter).orphan(listOf(event1.id!!, event2.id!!))
        order.verify(trainingEventRepository).deleteAll(events)

        val captor = ArgumentCaptor.forClass(TrainingBulkDeletedEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals(2, captor.value.count)
        assertEquals(actor, captor.value.actor)
    }

    @Test
    fun `bulk removing empty list does nothing`() {
        val removed = persistence.bulkRemove(emptyList(), actor)

        assertEquals(0, removed)
        verifyNoInteractions(trainingRecordWriter)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `bulk updating sessions saves them, syncs their training records, and publishes a single bulk event`() {
        val event1 = event()
        val event2 = event()
        val events = listOf(event1, event2)
        `when`(trainingEventRepository.saveAll(events)).thenReturn(events)

        val updated = persistence.bulkUpdate(events, "event type", actor)

        assertEquals(2, updated.size)
        val order: InOrder = inOrder(trainingEventRepository, trainingRecordWriter)
        order.verify(trainingEventRepository).saveAll(events)
        order.verify(trainingRecordWriter).sync(event1)
        order.verify(trainingRecordWriter).sync(event2)

        val captor = ArgumentCaptor.forClass(TrainingBulkUpdatedEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals(2, captor.value.count)
        assertEquals("event type", captor.value.updateType)
        assertEquals(actor, captor.value.actor)
    }
}
