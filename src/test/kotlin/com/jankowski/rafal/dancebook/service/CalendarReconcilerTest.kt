package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.CalendarSource
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventSourceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class CalendarReconcilerTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingEventPersistence: TrainingEventPersistence
    private lateinit var appUserService: AppUserService
    private lateinit var trainingEventSourceRepository: TrainingEventSourceRepository
    private lateinit var reconciler: CalendarReconciler

    private lateinit var targetCalendar: TrainingCalendar
    private lateinit var otherCalendar: TrainingCalendar
    private lateinit var rootAdmin: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingEventPersistence = mock(TrainingEventPersistence::class.java)
        appUserService = mock(AppUserService::class.java)
        trainingEventSourceRepository = mock(TrainingEventSourceRepository::class.java)

        reconciler = CalendarReconciler(
            trainingEventRepository,
            trainingEventPersistence,
            appUserService,
            trainingEventSourceRepository
        )

        targetCalendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Target Calendar"
            enabled = true
        }.also { cal ->
            val src = CalendarSource().apply {
                this.calendar = cal
                googleCalendarId = "target-cal@group.calendar.google.com"
                isWriteTarget = true
            }
            cal.sources.add(src)
        }

        otherCalendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Other Calendar"
            enabled = true
        }.also { cal ->
            val src = CalendarSource().apply {
                this.calendar = cal
                googleCalendarId = "other-cal@group.calendar.google.com"
                isWriteTarget = true
            }
            cal.sources.add(src)
        }

        rootAdmin = AppUser().apply {
            id = UUID.randomUUID()
            username = "rootadmin"
            displayName = "System Administrator"
            role = Role.ADMIN
        }

        `when`(appUserService.getRootAdmin()).thenReturn(rootAdmin)
    }

    @Test
    fun `should adopt new event as plain TRAINING and PLANNED session on given calendar`() {
        val googleId = "new-google-event"
        val start = LocalDateTime.of(2026, 9, 15, 18, 0)
        val end = LocalDateTime.of(2026, 9, 15, 20, 0)

        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.empty())

        val changeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = googleId,
                    title = "Group Class",
                    start = start,
                    end = end,
                    description = "Bring water"
                )
            ),
            nextSyncToken = "token-1",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(1, result.adopted)
        assertEquals(0, result.updated)
        assertEquals(0, result.deleted)
        assertEquals(0, result.skippedNoOp)

        val captor = ArgumentCaptor.forClass(TrainingEvent::class.java)
        verify(trainingEventPersistence).insert(captor.capture() ?: TrainingEvent(), org.mockito.ArgumentMatchers.eq(rootAdmin) ?: rootAdmin)

        val saved = captor.value
        assertEquals(googleId, saved.googleEventId)
        assertEquals("Group Class", saved.title)
        assertEquals(start, saved.startTime)
        assertEquals(end, saved.endTime)
        assertEquals("Bring water", saved.description)
        assertEquals(TrainingEventType.TRAINING, saved.eventType)
        assertEquals(AttendanceStatus.PLANNED, saved.attendanceFor(rootAdmin))
        assertEquals(targetCalendar.id, saved.calendar?.id)
        assertEquals(rootAdmin, saved.createdBy)
        assertTrue(saved.segments.isEmpty())
    }

    @Test
    fun `should update known event title, start and end without touching description or eventType`() {
        val googleId = "existing-google-event"
        val oldUpdatedAt = LocalDateTime.of(2026, 1, 1, 10, 0)
        val existing = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = googleId
            title = "Old Title"
            startTime = LocalDateTime.of(2026, 9, 15, 18, 0)
            endTime = LocalDateTime.of(2026, 9, 15, 19, 0)
            description = "Original Description\nType: WORKSHOP"
            eventType = TrainingEventType.WORKSHOP
            setAttendance(rootAdmin, AttendanceStatus.ATTENDED)
            calendar = targetCalendar
            createdBy = rootAdmin
            updatedAt = oldUpdatedAt
        }

        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.of(existing))

        val newStart = LocalDateTime.of(2026, 9, 15, 19, 0)
        val newEnd = LocalDateTime.of(2026, 9, 15, 20, 30)

        val changeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = googleId,
                    title = "New Title",
                    start = newStart,
                    end = newEnd,
                    description = "Google Formatted Description"
                )
            ),
            nextSyncToken = "token-2",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.adopted)
        assertEquals(1, result.updated)
        assertEquals(0, result.deleted)
        assertEquals(0, result.skippedNoOp)

        verify(trainingEventPersistence).applyUpdate(existing, rootAdmin)
        assertEquals("New Title", existing.title)
        assertEquals(newStart, existing.startTime)
        assertEquals(newEnd, existing.endTime)
        assertTrue(existing.updatedAt.isAfter(oldUpdatedAt), "updatedAt must be explicitly updated")
        // Description must NOT be overwritten for known events (Rule 2)
        assertEquals("Original Description\nType: WORKSHOP", existing.description)
        // Event type and attendance status are preserved (Rule 1)
        assertEquals(TrainingEventType.WORKSHOP, existing.eventType)
        assertEquals(AttendanceStatus.ATTENDED, existing.attendanceFor(rootAdmin))
    }

    @Test
    fun `should skip no-op when title, start and end match stored event`() {
        val googleId = "noop-google-event"
        val start = LocalDateTime.of(2026, 9, 15, 18, 0)
        val end = LocalDateTime.of(2026, 9, 15, 19, 0)

        val existing = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = googleId
            title = "Unchanged Practice"
            startTime = start
            endTime = end
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.of(existing))

        val changeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = googleId,
                    title = "Unchanged Practice",
                    start = start,
                    end = end,
                    description = "Any description"
                )
            ),
            nextSyncToken = "token-3",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.adopted)
        assertEquals(0, result.updated)
        assertEquals(0, result.deleted)
        assertEquals(1, result.skippedNoOp)

        // No database writes and no domain events
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `should trim segments from the end when session is shortened below segment total`() {
        val googleId = "shortened-event"
        val cat1 = DanceCategory().apply { name = "Standard" }
        val cat2 = DanceCategory().apply { name = "Latin" }

        val seg1 = TrainingEventSegment().apply {
            danceCategory = cat1
            durationMinutes = 60
            sortOrder = 0
        }
        val seg2 = TrainingEventSegment().apply {
            danceCategory = cat2
            durationMinutes = 60
            sortOrder = 1
        }

        val existing = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = googleId
            title = "Combined Session"
            startTime = LocalDateTime.of(2026, 9, 15, 18, 0)
            endTime = LocalDateTime.of(2026, 9, 15, 20, 0) // 120 min
            segments = mutableListOf(seg1, seg2)
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.of(existing))

        // Shorten to 90 min (18:00 to 19:30). Total segments = 120 > 90.
        // Dropping seg2 (60 min) leaves seg1 (60 min) <= 90 min.
        val changeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = googleId,
                    title = "Combined Session",
                    start = LocalDateTime.of(2026, 9, 15, 18, 0),
                    end = LocalDateTime.of(2026, 9, 15, 19, 30),
                    description = null
                )
            ),
            nextSyncToken = "token-4",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(1, result.updated)
        assertEquals(1, existing.segments.size)
        assertEquals("Standard", existing.segments[0].danceCategory?.name)
        assertEquals(60, existing.segments[0].durationMinutes)
        verify(trainingEventPersistence).applyUpdate(existing, rootAdmin)
    }

    @Test
    fun `should route deletion of known event through persistence remove`() {
        val googleId = "delete-me"
        val existing = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = googleId
            title = "Session To Delete"
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.of(existing))

        val changeSet = CalendarChangeSet(
            changes = listOf(CalendarChange.Cancelled(googleEventId = googleId)),
            nextSyncToken = "token-5",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(1, result.deleted)
        verify(trainingEventPersistence).remove(existing, rootAdmin)
    }

    @Test
    fun `should ignore deletion of unknown event`() {
        val googleId = "unknown-cancel"
        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.empty())

        val changeSet = CalendarChangeSet(
            changes = listOf(CalendarChange.Cancelled(googleEventId = googleId)),
            nextSyncToken = "token-6",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `should never modify or delete an event belonging to another calendar`() {
        val googleId = "foreign-event"
        val existing = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = googleId
            title = "Foreign Event"
            calendar = otherCalendar
        }

        `when`(trainingEventRepository.findByGoogleEventId(googleId)).thenReturn(Optional.of(existing))

        // Upsert change received for targetCalendar
        val changeSetUpsert = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = googleId,
                    title = "Changed Title",
                    start = LocalDateTime.of(2026, 9, 15, 10, 0),
                    end = LocalDateTime.of(2026, 9, 15, 11, 0),
                    description = null
                )
            ),
            nextSyncToken = "token-7",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val resultUpsert = reconciler.reconcile(targetCalendar, changeSetUpsert)
        assertEquals(0, resultUpsert.updated)
        verify(trainingEventPersistence, never()).applyUpdate(existing, rootAdmin)

        // Cancel change received for targetCalendar
        val changeSetCancel = CalendarChangeSet(
            changes = listOf(CalendarChange.Cancelled(googleEventId = googleId)),
            nextSyncToken = "token-8",
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = null
        )

        val resultCancel = reconciler.reconcile(targetCalendar, changeSetCancel)
        assertEquals(0, resultCancel.deleted)
        verify(trainingEventPersistence, never()).remove(existing, rootAdmin)
    }

    // --- Rule 5: Infer deletions on full resync ---

    @Test
    fun `Rule 5 - an event outside the fetched window is not deleted`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val oldEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "old-google-id"
            title = "Older than 1 year"
            startTime = windowStart.minusDays(1)
            endTime = windowStart.minusDays(1).plusHours(1)
            createdAt = fixedNow.minusDays(400)
            updatedAt = fixedNow.minusDays(400)
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!)).thenReturn(listOf(oldEvent))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        verify(trainingEventPersistence, never()).remove(oldEvent, rootAdmin)
    }

    @Test
    fun `Rule 5 - an incomplete fetch deletes nothing at all`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val event = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "absent-google-id"
            title = "Absent Event"
            startTime = fixedNow.minusMonths(2)
            endTime = fixedNow.minusMonths(2).plusHours(1)
            createdAt = fixedNow.minusDays(10)
            updatedAt = fixedNow.minusDays(10)
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!)).thenReturn(listOf(event))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = null,
            fullResyncRequired = false,
            isCompleteWindow = false,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        verify(trainingEventPersistence, never()).remove(event, rootAdmin)
        verify(trainingEventRepository, never()).findAllByCalendarId(targetCalendar.id!!)
    }

    @Test
    fun `Rule 5 - another calendar's events are never considered`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val otherEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "other-cal-google-id"
            title = "Other Calendar Event"
            startTime = fixedNow.minusMonths(2)
            endTime = fixedNow.minusMonths(2).plusHours(1)
            createdAt = fixedNow.minusDays(10)
            updatedAt = fixedNow.minusDays(10)
            calendar = otherCalendar
        }

        // findAllByCalendarId for targetCalendar returns empty list
        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!)).thenReturn(emptyList())

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        verify(trainingEventPersistence, never()).remove(otherEvent, rootAdmin)
    }

    @Test
    fun `Rule 5 - a just-created or just-updated event inside the grace period survives`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val justCreated = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "just-created-google-id"
            title = "Just Created Event"
            startTime = fixedNow.minusMonths(1)
            endTime = fixedNow.minusMonths(1).plusHours(1)
            createdAt = fixedNow.minusSeconds(30) // 30s ago
            updatedAt = fixedNow.minusSeconds(30)
            calendar = targetCalendar
        }

        val justUpdated = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "just-updated-google-id"
            title = "Just Updated Event"
            startTime = fixedNow.minusMonths(2)
            endTime = fixedNow.minusMonths(2).plusHours(1)
            createdAt = fixedNow.minusDays(5)
            updatedAt = fixedNow.minusSeconds(15) // 15s ago
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!))
            .thenReturn(listOf(justCreated, justUpdated))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        verify(trainingEventPersistence, never()).remove(justCreated, rootAdmin)
        verify(trainingEventPersistence, never()).remove(justUpdated, rootAdmin)
    }

    @Test
    fun `Rule 5 - an event with no google_event_id is never deleted`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val nullGoogleIdEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = null
            title = "Failed Create Event"
            startTime = fixedNow.minusMonths(1)
            endTime = fixedNow.minusMonths(1).plusHours(1)
            createdAt = fixedNow.minusDays(5)
            updatedAt = fixedNow.minusDays(5)
            calendar = targetCalendar
        }

        val blankGoogleIdEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "   "
            title = "Blank Google Id Event"
            startTime = fixedNow.minusMonths(1)
            endTime = fixedNow.minusMonths(1).plusHours(1)
            createdAt = fixedNow.minusDays(5)
            updatedAt = fixedNow.minusDays(5)
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!))
            .thenReturn(listOf(nullGoogleIdEvent, blankGoogleIdEvent))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        verify(trainingEventPersistence, never()).remove(nullGoogleIdEvent, rootAdmin)
        verify(trainingEventPersistence, never()).remove(blankGoogleIdEvent, rootAdmin)
    }

    @Test
    fun `Rule 5 - an event genuinely absent from a complete window is deleted`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val absentEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "absent-from-google"
            title = "Deleted From Google Remotely"
            startTime = fixedNow.minusMonths(3)
            endTime = fixedNow.minusMonths(3).plusHours(1)
            createdAt = fixedNow.minusDays(10)
            updatedAt = fixedNow.minusDays(10)
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!))
            .thenReturn(listOf(absentEvent))

        val changeSet = CalendarChangeSet(
            changes = emptyList(),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(1, result.deleted)
        verify(trainingEventPersistence).remove(absentEvent, rootAdmin)
    }

    @Test
    fun `Rule 5 - an event returned by Google in changeSet is not deleted as absent`() {
        val fixedNow = LocalDateTime.of(2026, 9, 15, 12, 0)
        reconciler.nowProvider = { fixedNow }
        val windowStart = fixedNow.minusYears(1)

        val presentEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            googleEventId = "present-in-google"
            title = "Present Event"
            startTime = fixedNow.minusMonths(3)
            endTime = fixedNow.minusMonths(3).plusHours(1)
            createdAt = fixedNow.minusDays(10)
            updatedAt = fixedNow.minusDays(10)
            calendar = targetCalendar
        }

        `when`(trainingEventRepository.findByGoogleEventId("present-in-google"))
            .thenReturn(Optional.of(presentEvent))
        `when`(trainingEventRepository.findAllByCalendarId(targetCalendar.id!!))
            .thenReturn(listOf(presentEvent))

        val changeSet = CalendarChangeSet(
            changes = listOf(
                CalendarChange.Upserted(
                    googleEventId = "present-in-google",
                    title = "Present Event",
                    start = presentEvent.startTime,
                    end = presentEvent.endTime,
                    description = null
                )
            ),
            nextSyncToken = "new-token",
            fullResyncRequired = false,
            isCompleteWindow = true,
            windowStart = windowStart
        )

        val result = reconciler.reconcile(targetCalendar, changeSet)

        assertEquals(0, result.deleted)
        assertEquals(1, result.skippedNoOp)
        verify(trainingEventPersistence, never()).remove(presentEvent, rootAdmin)
    }
}
