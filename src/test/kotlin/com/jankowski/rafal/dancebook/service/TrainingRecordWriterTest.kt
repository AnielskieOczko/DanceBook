package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyCollection
import org.mockito.Mockito.never
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.UUID

class TrainingRecordWriterTest {

    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var writer: TrainingRecordWriter
    private lateinit var owner: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        writer = TrainingRecordWriter(trainingRecordRepository)
        owner = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
    }

    private fun event(
        status: AttendanceStatus,
        minutes: Long = 90,
        title: String = "Monday practice",
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): TrainingEvent {
        val start = LocalDateTime.of(2026, 9, 7, 18, 0)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            this.title = title
            startTime = start
            endTime = start.plusMinutes(minutes)
            attendanceStatus = status
            eventType = type
            createdBy = owner
            this.segments = segments.mapIndexed { index, (sliceCategory, sliceMinutes) ->
                TrainingEventSegment().apply {
                    danceCategory = sliceCategory
                    durationMinutes = sliceMinutes
                    sortOrder = index
                }
            }.toMutableList()
        }
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun savedRecord(): TrainingRecord {
        val captor = ArgumentCaptor.forClass(TrainingRecord::class.java)
        verify(trainingRecordRepository).save(captor.capture())
        return captor.value
    }

    @Test
    fun `marking a session attended writes a record snapshotting the session`() {
        val standard = category("Standard")
        val source = event(AttendanceStatus.ATTENDED, minutes = 120, segments = listOf(standard to 60))
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(null)

        writer.sync(source)

        val record = savedRecord()
        assertEquals(source.id, record.trainingEventId)
        assertEquals(TrainingOutcome.ATTENDED, record.outcome)
        assertEquals("Monday practice", record.title)
        assertEquals(120, record.durationMinutes)
        assertEquals(source.startTime, record.occurredAt)
        assertEquals(TrainingEventType.TRAINING, record.eventType)
        assertEquals(owner, record.createdBy)
        assertNull(record.orphanedAt)
        assertEquals(1, record.segments.size)
        assertEquals("Standard", record.segments.first().categoryName)
        assertEquals(60, record.segments.first().durationMinutes)
        assertEquals(standard, record.segments.first().danceCategory)
    }

    @Test
    fun `marking a session skipped writes a skipped record`() {
        val source = event(AttendanceStatus.SKIPPED)
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(null)

        writer.sync(source)

        assertEquals(TrainingOutcome.SKIPPED, savedRecord().outcome)
    }

    @Test
    fun `editing a confirmed session updates its record in place`() {
        val latin = category("Latin")
        val source = event(AttendanceStatus.ATTENDED, minutes = 60, title = "Retitled", segments = listOf(latin to 45))
        val existing = TrainingRecord().apply {
            id = UUID.randomUUID()
            trainingEventId = source.id
            outcome = TrainingOutcome.SKIPPED
            title = "Old title"
            durationMinutes = 30
            createdBy = owner
        }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        val record = savedRecord()
        assertEquals(existing.id, record.id, "the same row is rewritten, not a second one appended")
        assertEquals(TrainingOutcome.ATTENDED, record.outcome)
        assertEquals("Retitled", record.title)
        assertEquals(60, record.durationMinutes)
        assertEquals(listOf("Latin"), record.segments.map { it.categoryName })
    }

    @Test
    fun `marking a session back to planned removes its record`() {
        val source = event(AttendanceStatus.PLANNED)
        val existing = TrainingRecord().apply { trainingEventId = source.id }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        verify(trainingRecordRepository).delete(existing)
        verify(trainingRecordRepository, never()).save(any(TrainingRecord::class.java))
    }

    @Test
    fun `marking a session cancelled removes its record`() {
        val source = event(AttendanceStatus.CANCELLED)
        val existing = TrainingRecord().apply { trainingEventId = source.id }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        verify(trainingRecordRepository).delete(existing)
    }

    @Test
    fun `a planned session with no record writes nothing`() {
        val source = event(AttendanceStatus.PLANNED)
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(null)

        writer.sync(source)

        verify(trainingRecordRepository, never()).save(any(TrainingRecord::class.java))
        verify(trainingRecordRepository, never()).delete(any(TrainingRecord::class.java))
    }

    @Test
    fun `an orphaned record is frozen and never rewritten`() {
        val source = event(AttendanceStatus.ATTENDED, title = "Late edit")
        val existing = TrainingRecord().apply {
            trainingEventId = source.id
            title = "As recorded"
            orphanedAt = LocalDateTime.now()
        }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        verify(trainingRecordRepository, never()).save(any(TrainingRecord::class.java))
        assertEquals("As recorded", existing.title)
    }

    @Test
    fun `deleting sessions orphans their records instead of removing them`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val records = listOf(
            TrainingRecord().apply { trainingEventId = first },
            TrainingRecord().apply { trainingEventId = second }
        )
        `when`(trainingRecordRepository.findAllByTrainingEventIdIn(listOf(first, second))).thenReturn(records)

        writer.orphan(listOf(first, second))

        verify(trainingRecordRepository).saveAll(records)
        records.forEach { assertNotNull(it.orphanedAt) }
        assertTrue(records.all { it.isOrphaned })
    }

    @Test
    fun `orphaning an already-orphaned record does not restamp it`() {
        val id = UUID.randomUUID()
        val stamped = LocalDateTime.of(2026, 1, 1, 12, 0)
        val existing = TrainingRecord().apply {
            trainingEventId = id
            orphanedAt = stamped
        }
        `when`(trainingRecordRepository.findAllByTrainingEventIdIn(listOf(id))).thenReturn(listOf(existing))

        writer.orphan(listOf(id))

        // Left exactly as it was: a second delete must not move the date on which the
        // record's session disappeared.
        assertEquals(stamped, existing.orphanedAt)
    }

    @Test
    fun `orphaning nothing touches the database not at all`() {
        writer.orphan(emptyList())

        verify(trainingRecordRepository, never()).findAllByTrainingEventIdIn(anyCollection())
    }
}
