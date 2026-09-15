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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CalendarSyncServiceTest {

    private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private lateinit var calendarReconciler: CalendarReconciler
    private lateinit var systemSettingService: SystemSettingService
    private lateinit var testClock: MutableClock
    private lateinit var syncService: CalendarSyncServiceImpl

    private lateinit var cal1: TrainingCalendar
    private lateinit var cal2: TrainingCalendar

    class MutableClock(var currentInstant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId?): Clock = MutableClock(currentInstant, zone ?: this.zone)
        override fun instant(): Instant = currentInstant
        fun advance(duration: Duration) {
            currentInstant = currentInstant.plus(duration)
        }
    }

    @BeforeEach
    fun setUp() {
        trainingCalendarRepository = mock(TrainingCalendarRepository::class.java)
        googleCalendarClient = mock(GoogleCalendarClient::class.java)
        calendarReconciler = mock(CalendarReconciler::class.java)
        systemSettingService = mock(SystemSettingService::class.java)
        testClock = MutableClock(Instant.parse("2026-09-15T12:00:00Z"))

        `when`(systemSettingService.getIntSetting("calendar_sync_interval_seconds", 60)).thenReturn(60)

        syncService = CalendarSyncServiceImpl(
            trainingCalendarRepository,
            googleCalendarClient,
            calendarReconciler,
            systemSettingService,
            testClock
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
    fun `expired token triggers full resync clearing stored token and delegating full change set to reconciler`() {
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

    @Test
    fun `syncIfDue runs when interval has elapsed and does not when it has not`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1")).thenReturn(changeSet)
        `when`(calendarReconciler.reconcile(cal1, changeSet)).thenReturn(ReconcileResult(updated = 1))

        // First call: never synced before, so it runs
        val report1 = syncService.syncIfDue()
        assertNotNull(report1)
        assertEquals(1, report1?.outcomes?.size)
        verify(googleCalendarClient, times(1)).listChanges("club@google.com", "token-club-1")

        // 30 seconds later (< 60s default): throttled, returns null, no client call
        testClock.advance(Duration.ofSeconds(30))
        val report2 = syncService.syncIfDue()
        assertNull(report2)
        verify(googleCalendarClient, times(1)).listChanges("club@google.com", "token-club-1")

        // 31 seconds later (total 61s > 60s): runs again
        testClock.advance(Duration.ofSeconds(31))
        cal1.syncToken = "token-club-2"
        val changeSet3 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-3",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-2")).thenReturn(changeSet3)
        `when`(calendarReconciler.reconcile(cal1, changeSet3)).thenReturn(ReconcileResult())

        val report3 = syncService.syncIfDue()
        assertNotNull(report3)
        verify(googleCalendarClient, times(1)).listChanges("club@google.com", "token-club-2")
    }

    @Test
    fun `Sync now bypasses the throttle and resets the interval timer`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1))

        val changeSet1 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1")).thenReturn(changeSet1)
        `when`(calendarReconciler.reconcile(cal1, changeSet1)).thenReturn(ReconcileResult())

        // Initial syncIfDue runs
        val report1 = syncService.syncIfDue()
        assertNotNull(report1)

        // Only 5 seconds pass (throttle is 60s)
        testClock.advance(Duration.ofSeconds(5))
        assertNull(syncService.syncIfDue())

        // Sync now bypasses throttle!
        cal1.syncToken = "token-club-2"
        val changeSet2 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-3",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-2")).thenReturn(changeSet2)
        `when`(calendarReconciler.reconcile(cal1, changeSet2)).thenReturn(ReconcileResult())

        val reportSyncNow = syncService.syncAll()
        assertFalse(reportSyncNow.hasFailures)
        verify(googleCalendarClient).listChanges("club@google.com", "token-club-2")

        // 10 seconds after "Sync now": syncIfDue is throttled because "Sync now" reset the timer
        testClock.advance(Duration.ofSeconds(10))
        assertNull(syncService.syncIfDue())

        // 51 seconds later (total 61s after "Sync now"): syncIfDue runs again
        testClock.advance(Duration.ofSeconds(51))
        cal1.syncToken = "token-club-3"
        val changeSet3 = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-4",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-3")).thenReturn(changeSet3)
        `when`(calendarReconciler.reconcile(cal1, changeSet3)).thenReturn(ReconcileResult())

        assertNotNull(syncService.syncIfDue())
    }

    @Test
    fun `HTMX fragment burst triggers at most one sync run`() {
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc())
            .thenReturn(listOf(cal1))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "token-club-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )
        `when`(googleCalendarClient.listChanges("club@google.com", "token-club-1")).thenAnswer {
            // Simulate work during sync call to allow race condition / burst testing
            Thread.sleep(50)
            changeSet
        }
        `when`(calendarReconciler.reconcile(cal1, changeSet)).thenReturn(ReconcileResult())

        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = mutableListOf<SyncReport?>()

        for (i in 0 until threadCount) {
            executor.submit {
                startLatch.await()
                try {
                    val res = syncService.syncIfDue()
                    synchronized(results) {
                        results.add(res)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Exactly one thread should have executed sync, others returned null
        val executedRuns = results.filterNotNull()
        assertEquals(1, executedRuns.size, "Burst should trigger exactly 1 sync run")
        verify(googleCalendarClient, times(1)).listChanges("club@google.com", "token-club-1")
    }
}
