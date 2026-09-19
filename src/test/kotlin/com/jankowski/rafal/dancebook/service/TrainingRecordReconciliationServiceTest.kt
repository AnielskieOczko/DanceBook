package com.jankowski.rafal.dancebook.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class TrainingRecordReconciliationServiceTest {

    @Mock
    private lateinit var trainingRecordRepository: TrainingRecordRepository

    @Mock
    private lateinit var trainingEventRepository: TrainingEventRepository

    @Captor
    private lateinit var recordsCaptor: ArgumentCaptor<List<TrainingRecord>>

    private lateinit var service: TrainingRecordReconciliationServiceImpl

    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logbackLogger: Logger

    @BeforeEach
    fun setUp() {
        service = TrainingRecordReconciliationServiceImpl(trainingRecordRepository, trainingEventRepository)

        logbackLogger = LoggerFactory.getLogger(TrainingRecordReconciliationServiceImpl::class.java) as Logger
        listAppender = ListAppender()
        listAppender.start()
        logbackLogger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logbackLogger.detachAppender(listAppender)
        listAppender.stop()
    }

    @Test
    fun `repairs records missing calendar whose session has one and logs count`() {
        val calendarId = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            id = calendarId
            displayName = "Club Calendar"
        }

        val eventId = UUID.randomUUID()
        val event = TrainingEvent().apply {
            id = eventId
            this.calendar = calendar
        }

        val record = TrainingRecord().apply {
            id = UUID.randomUUID()
            trainingEventId = eventId
            this.calendarId = null
            this.calendarName = null
        }

        `when`(trainingRecordRepository.findAllByCalendarIdIsNullAndOrphanedAtIsNull())
            .thenReturn(listOf(record))
        `when`(trainingEventRepository.findAllById(listOf(eventId)))
            .thenReturn(listOf(event))

        val count = service.reconcile()

        assertEquals(1, count)
        verify(trainingRecordRepository).saveAll(recordsCaptor.capture())
        val saved = recordsCaptor.value
        assertEquals(1, saved.size)
        assertEquals(calendarId, saved.first().calendarId)
        assertEquals("Club Calendar", saved.first().calendarName)

        val logs = listAppender.list.map { it.formattedMessage }
        assertTrue(logs.any { it.contains("Repaired 1 training records with missing calendar references") })
    }

    @Test
    fun `leaves orphaned records untouched`() {
        val orphanRecord = TrainingRecord().apply {
            id = UUID.randomUUID()
            trainingEventId = UUID.randomUUID()
            orphanedAt = LocalDateTime.now()
        }

        // Repository returns it if somehow not filtered in SQL, or query filters it
        `when`(trainingRecordRepository.findAllByCalendarIdIsNullAndOrphanedAtIsNull())
            .thenReturn(listOf(orphanRecord))

        val count = service.reconcile()

        assertEquals(0, count)
        verify(trainingRecordRepository, never()).saveAll(recordsCaptor.capture())
        assertNull(orphanRecord.calendarId)
        assertNull(orphanRecord.calendarName)
        assertTrue(listAppender.list.isEmpty(), "logs nothing when zero repaired")
    }

    @Test
    fun `leaves record untouched when its session is deleted`() {
        val missingEventId = UUID.randomUUID()
        val record = TrainingRecord().apply {
            id = UUID.randomUUID()
            trainingEventId = missingEventId
            calendarId = null
            calendarName = null
        }

        `when`(trainingRecordRepository.findAllByCalendarIdIsNullAndOrphanedAtIsNull())
            .thenReturn(listOf(record))
        `when`(trainingEventRepository.findAllById(listOf(missingEventId)))
            .thenReturn(emptyList())

        val count = service.reconcile()

        assertEquals(0, count)
        verify(trainingRecordRepository, never()).saveAll(recordsCaptor.capture())
        assertNull(record.calendarId)
        assertNull(record.calendarName)
        assertTrue(listAppender.list.isEmpty(), "logs nothing when zero repaired")
    }

    @Test
    fun `leaves record untouched when its session has no calendar`() {
        val eventId = UUID.randomUUID()
        val event = TrainingEvent().apply {
            id = eventId
            calendar = null
        }

        val record = TrainingRecord().apply {
            id = UUID.randomUUID()
            trainingEventId = eventId
            calendarId = null
            calendarName = null
        }

        `when`(trainingRecordRepository.findAllByCalendarIdIsNullAndOrphanedAtIsNull())
            .thenReturn(listOf(record))
        `when`(trainingEventRepository.findAllById(listOf(eventId)))
            .thenReturn(listOf(event))

        val count = service.reconcile()

        assertEquals(0, count)
        verify(trainingRecordRepository, never()).saveAll(recordsCaptor.capture())
        assertNull(record.calendarId)
        assertNull(record.calendarName)
        assertTrue(listAppender.list.isEmpty(), "logs nothing when zero repaired")
    }

    @Test
    fun `second pass repairs nothing and logs nothing`() {
        `when`(trainingRecordRepository.findAllByCalendarIdIsNullAndOrphanedAtIsNull())
            .thenReturn(emptyList())

        val count = service.reconcile()

        assertEquals(0, count)
        verify(trainingRecordRepository, never()).saveAll(recordsCaptor.capture())
        assertTrue(listAppender.list.isEmpty(), "logs nothing when zero repaired")
    }
}
