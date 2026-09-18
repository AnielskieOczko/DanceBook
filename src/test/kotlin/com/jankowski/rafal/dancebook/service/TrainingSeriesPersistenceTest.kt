package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingSeriesRepository
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.util.UUID

/**
 * The two bulk series methods publish no domain event, so nothing else would notice that a
 * series delete had taken a month of recorded training with it. These are that check.
 */
class TrainingSeriesPersistenceTest {

    private lateinit var trainingSeriesRepository: TrainingSeriesRepository
    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingRecordWriter: TrainingRecordWriter
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var persistence: TrainingSeriesPersistence
    private lateinit var actor: AppUser

    @BeforeEach
    fun setUp() {
        trainingSeriesRepository = mock(TrainingSeriesRepository::class.java)
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingRecordWriter = mock(TrainingRecordWriter::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        persistence = TrainingSeriesPersistence(
            trainingSeriesRepository, trainingEventRepository, trainingRecordWriter, eventPublisher
        )
        actor = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
    }

    private fun occurrence(): TrainingEvent {
        val start = LocalDateTime.of(2026, 9, 7, 18, 0)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
            startTime = start
            endTime = start.plusMinutes(90)
            attendanceStatus = AttendanceStatus.ATTENDED
            createdBy = actor
        }
    }

    @Test
    fun `deleting occurrences in bulk orphans their records`() {
        val first = occurrence()
        val second = occurrence()

        persistence.removeOccurrences(listOf(first, second))

        verify(trainingRecordWriter).orphan(listOf(first.id!!, second.id!!))
        verify(trainingEventRepository).deleteAll(listOf(first, second))
    }

    @Test
    fun `removeOccurrences deletes series and publishes event with seriesRemoved true when no remaining occurrences`() {
        val series = TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Weekly Practice"
        }
        val first = occurrence().apply { this.series = series }
        `when`(trainingEventRepository.countBySeries(series)).thenReturn(0L)

        persistence.removeOccurrences(listOf(first), series, actor)

        verify(trainingSeriesRepository).delete(series)
        val eventCaptor = ArgumentCaptor.forClass(com.jankowski.rafal.dancebook.model.TrainingSeriesDeletedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        org.junit.jupiter.api.Assertions.assertEquals("Weekly Practice", eventCaptor.value.seriesTitle)
        org.junit.jupiter.api.Assertions.assertEquals(1, eventCaptor.value.deletedCount)
        org.junit.jupiter.api.Assertions.assertEquals(true, eventCaptor.value.seriesRemoved)
    }

    @Test
    fun `removeOccurrences keeps series and publishes delete event with seriesRemoved false when occurrences remain`() {
        val series = TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Weekly Practice"
        }
        val first = occurrence().apply { this.series = series }
        `when`(trainingEventRepository.countBySeries(series)).thenReturn(2L)

        persistence.removeOccurrences(listOf(first), series, actor)

        verify(trainingSeriesRepository, never()).delete(series)
        val eventCaptor = ArgumentCaptor.forClass(com.jankowski.rafal.dancebook.model.TrainingSeriesDeletedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        org.junit.jupiter.api.Assertions.assertEquals("Weekly Practice", eventCaptor.value.seriesTitle)
        org.junit.jupiter.api.Assertions.assertEquals(1, eventCaptor.value.deletedCount)
        org.junit.jupiter.api.Assertions.assertEquals(false, eventCaptor.value.seriesRemoved)
    }

    @Test
    fun `deleteAll detaches surviving events, orphans and deletes non-surviving events, and deletes series`() {
        val series = TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Weekly Practice"
        }
        val surviving = occurrence().apply {
            this.series = series
            attendanceStatus = AttendanceStatus.ATTENDED
        }
        val toDelete = occurrence().apply {
            this.series = series
            attendanceStatus = AttendanceStatus.PLANNED
        }

        persistence.deleteAll(series, listOf(surviving), listOf(toDelete), actor)

        assertNull(surviving.series, "surviving event is detached from the series")
        assertNull(toDelete.series)
        verify(trainingEventRepository).saveAll(listOf(surviving))
        verify(trainingRecordWriter).orphan(listOf(toDelete.id!!))
        verify(trainingEventRepository).deleteAll(listOf(toDelete))
        verify(trainingSeriesRepository).delete(series)

        val eventCaptor = ArgumentCaptor.forClass(com.jankowski.rafal.dancebook.model.TrainingSeriesDeletedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        org.junit.jupiter.api.Assertions.assertEquals("Weekly Practice", eventCaptor.value.seriesTitle)
        org.junit.jupiter.api.Assertions.assertEquals(1, eventCaptor.value.deletedCount)
        org.junit.jupiter.api.Assertions.assertEquals(true, eventCaptor.value.seriesRemoved)
    }

    @Test
    fun `regenerating a series orphans the records of the occurrences it replaces`() {
        val series = TrainingSeries().apply { id = UUID.randomUUID() }
        val removed = occurrence()
        val added = occurrence()
        `when`(trainingEventRepository.saveAll(anyList())).thenReturn(mutableListOf(added))

        persistence.replaceOccurrences(series, listOf(removed), listOf(added))

        verify(trainingRecordWriter).orphan(listOf(removed.id!!))
    }
}
