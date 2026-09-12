package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class TrainingHistoryServiceTest {

    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingHistoryService: TrainingHistoryServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        appUserService = mock(AppUserService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            role = Role.USER
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingHistoryService = TrainingHistoryServiceImpl(trainingRecordRepository, appUserService)
    }

    private fun record(
        occurredAt: LocalDateTime,
        outcome: TrainingOutcome = TrainingOutcome.ATTENDED,
        minutes: Int = 60,
        owner: AppUser = currentUser,
        orphaned: Boolean = false
    ) = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = UUID.randomUUID()
        this.occurredAt = occurredAt
        durationMinutes = minutes
        this.outcome = outcome
        title = "Monday practice"
        eventType = TrainingEventType.TRAINING
        createdBy = owner
        if (orphaned) orphanedAt = LocalDateTime.now()
    }

    private fun given(vararg records: TrainingRecord) {
        `when`(trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser))
            .thenReturn(records.toList())
    }

    @Test
    fun `history is grouped into months, newest first`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0)),
            record(LocalDateTime.of(2026, 9, 3, 18, 0)),
            record(LocalDateTime.of(2026, 8, 27, 18, 0))
        )

        val months = trainingHistoryService.historyForCurrentUser().months

        assertEquals(listOf("September 2026", "August 2026"), months.map { it.label })
        assertEquals(2, months.first().sessionCount)
        assertEquals("2 sessions", months.first().sessionLabel)
        assertEquals("1 session", months.last().sessionLabel)
    }

    @Test
    fun `a month totals the hours actually trained, not the skipped ones`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), TrainingOutcome.ATTENDED, minutes = 90),
            record(LocalDateTime.of(2026, 9, 3, 18, 0), TrainingOutcome.SKIPPED, minutes = 120)
        )

        val month = trainingHistoryService.historyForCurrentUser().months.single()

        assertEquals(90L, month.attendedMinutes)
        assertEquals("1h 30m", month.totalLabel)
    }

    @Test
    fun `each row carries the palette swatch for its outcome`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), TrainingOutcome.ATTENDED),
            record(LocalDateTime.of(2026, 9, 3, 18, 0), TrainingOutcome.SKIPPED)
        )

        val rows = trainingHistoryService.historyForCurrentUser().months.single().rows

        assertEquals(TrainingEventPalette.ATTENDED, rows.first().swatch)
        assertEquals(TrainingEventPalette.SKIPPED, rows.last().swatch)
    }

    @Test
    fun `rows whose session has been deleted are marked orphaned and counted`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), orphaned = true),
            record(LocalDateTime.of(2026, 9, 3, 18, 0))
        )

        val history = trainingHistoryService.historyForCurrentUser()

        assertTrue(history.months.single().rows.first().isOrphaned)
        assertFalse(history.months.single().rows.last().isOrphaned)
        assertEquals(1, history.orphanedCount)
        assertEquals("This session has been deleted from your calendar.", history.orphanedExplanation)
    }

    @Test
    fun `the orphan explainer agrees in number with more than one deleted session`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), orphaned = true),
            record(LocalDateTime.of(2026, 9, 3, 18, 0), orphaned = true),
            record(LocalDateTime.of(2026, 8, 27, 18, 0))
        )

        val history = trainingHistoryService.historyForCurrentUser()

        assertEquals(2, history.orphanedCount)
        assertEquals("2 of these sessions have been deleted from your calendar.", history.orphanedExplanation)
    }

    @Test
    fun `an empty history reports itself empty`() {
        given()

        assertTrue(trainingHistoryService.historyForCurrentUser().isEmpty)
    }

    @Test
    fun `an orphaned record can be removed`() {
        val orphan = record(LocalDateTime.of(2026, 9, 10, 18, 0), orphaned = true)
        `when`(trainingRecordRepository.findById(orphan.id!!)).thenReturn(Optional.of(orphan))

        trainingHistoryService.deleteOrphanedRecord(orphan.id!!)

        verify(trainingRecordRepository).delete(orphan)
    }

    @Test
    fun `a record whose session still exists cannot be removed here`() {
        val linked = record(LocalDateTime.of(2026, 9, 10, 18, 0))
        `when`(trainingRecordRepository.findById(linked.id!!)).thenReturn(Optional.of(linked))

        assertThrows(IllegalStateException::class.java) {
            trainingHistoryService.deleteOrphanedRecord(linked.id!!)
        }
        verify(trainingRecordRepository, never()).delete(linked)
    }

    @Test
    fun `another user's record cannot be removed`() {
        val stranger = AppUser().apply {
            id = UUID.randomUUID()
            username = "someone-else"
        }
        val theirs = record(LocalDateTime.of(2026, 9, 10, 18, 0), owner = stranger, orphaned = true)
        `when`(trainingRecordRepository.findById(theirs.id!!)).thenReturn(Optional.of(theirs))

        assertThrows(IllegalStateException::class.java) {
            trainingHistoryService.deleteOrphanedRecord(theirs.id!!)
        }
        verify(trainingRecordRepository, never()).delete(theirs)
    }
}
