package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.UUID

class CalendarSyncServiceTest {

    private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private lateinit var calendarReconciler: CalendarReconciler
    private lateinit var syncService: CalendarSyncServiceImpl

    private lateinit var cal1: TrainingCalendar
    private lateinit var cal2: TrainingCalendar

    @BeforeEach
    fun setUp() {
        trainingCalendarRepository = mock(TrainingCalendarRepository::class.java)
        googleCalendarClient = mock(GoogleCalendarClient::class.java)
        calendarReconciler = mock(CalendarReconciler::class.java)

        syncService = CalendarSyncServiceImpl(
            trainingCalendarRepository,
            googleCalendarClient,
            calendarReconciler
        )

        cal1 = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Club Calendar"
            googleCalendarId = "club@google.com"
            syncToken = "token-club-1"
            enabled = true
        }

        cal2 = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Personal Calendar"
            googleCalendarId = "personal@google.com"
            syncToken = "token-personal-1"
            enabled = true
        }
    }

    @Test
    fun `syncAll syncs enabled calendars and updates sync tokens on success`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1, cal2))

        val changeSet1 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        val changeSet2 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-personal-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1")).thenReturn(changeSet1)
        `when`(googleCalendarClient.listChanges("personal@google.com", "token-personal-1")).thenReturn(changeSet2)

        `when`(calendarReconciler.reconcile(cal1, changeSet1))
            .thenReturn(ReconcileResult(adopted = 1, updated = 0, deleted = 0, skippedNoOp = 0))
        `when`(calendarReconciler.reconcile(cal2, changeSet2))
            .thenReturn(ReconcileResult(adopted = 0, updated = 2, deleted = 0, skippedNoOp = 1))

        val report = syncService.syncAll()

        assertFalse(report.hasFailures)
        assertEquals(2, report.outcomes.size)

        assertEquals("token-club-2", cal1.syncToken)
        assertNotNull(cal1.lastSyncedAt)
        verify(trainingCalendarRepository).save(cal1)

        assertEquals("token-personal-2", cal2.syncToken)
        assertNotNull(cal2.lastSyncedAt)
        verify(trainingCalendarRepository).save(cal2)
    }

    @Test
    fun `failure during reconcile leaves sync token unchanged for that calendar`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1")).thenReturn(changeSet)
        `when`(calendarReconciler.reconcile(cal1, changeSet))
            .thenThrow(RuntimeException("Database connection dropped"))

        val report = syncService.syncAll()

        assertTrue(report.hasFailures)
        assertEquals(1, report.outcomes.size)
        assertFalse(report.outcomes[0].success)
        assertEquals("Database connection dropped", report.outcomes[0].errorMessage)

        // Token must NOT have advanced!
        assertEquals("token-club-1", cal1.syncToken)
        assertNull(cal1.lastSyncedAt)
    }

    @Test
    fun `failure on one calendar does not stop syncing other calendars and surfaces in report`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1, cal2))

        // cal1 fails on client call
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1"))
            .thenThrow(CalendarSyncException("Google 503 Service Unavailable"))

        // cal2 succeeds
        val changeSet2 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-personal-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("personal@google.com", "token-personal-1")).thenReturn(changeSet2)
        `when`(calendarReconciler.reconcile(cal2, changeSet2)).thenReturn(ReconcileResult())

        val report = syncService.syncAll()

        assertTrue(report.hasFailures)
        assertEquals(2, report.outcomes.size)

        val outcome1 = report.outcomes.find { it.calendar.id == cal1.id }!!
        assertFalse(outcome1.success)
        assertTrue(outcome1.errorMessage!!.contains("503 Service Unavailable"))
        assertEquals("token-club-1", cal1.syncToken)

        val outcome2 = report.outcomes.find { it.calendar.id == cal2.id }!!
        assertTrue(outcome2.success)
        assertEquals("token-personal-2", cal2.syncToken)
        verify(trainingCalendarRepository).save(cal2)
    }

    @Test
    fun `expired token triggers full resync clearing stored token and adopting without deleting`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1))

        val expiredChangeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = null,
            fullResyncRequired = true,
            isCompleteWindow = false,
            windowStart = null
        )
        val fullChangeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "fresh-sync-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = java.time.LocalDateTime.now().minusYears(1)
        )

        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1")).thenReturn(expiredChangeSet)
        `when`(googleCalendarClient.listChanges("club@google.com", null)).thenReturn(fullChangeSet)
        `when`(calendarReconciler.reconcile(cal1, fullChangeSet)).thenReturn(ReconcileResult(adopted = 5))

        val report = syncService.syncAll()

        assertFalse(report.hasFailures)
        assertEquals("fresh-sync-token", cal1.syncToken)
        assertNotNull(cal1.lastSyncedAt)
        verify(trainingCalendarRepository, org.mockito.Mockito.times(2)).save(cal1)
    }

    @Test
    fun `syncAll is a no-op when no calendars are enabled`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc()).thenReturn(emptyList())

        val report = syncService.syncAll()

        assertFalse(report.hasFailures)
        assertTrue(report.outcomes.isEmpty())
        verifyNoInteractions(googleCalendarClient)
        verifyNoInteractions(calendarReconciler)
    }
}
