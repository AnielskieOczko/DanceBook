package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkEditResult
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftServiceImplTest {

    private val codec = AssistantDraftCodec(DraftTestSupport.mapper())

    /** Runs the callback with a real status object (a mocked manager returns null, which Kotlin's non-null lambda parameter refuses). */
    private val noTransactions = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) {}
        override fun rollback(status: TransactionStatus) {}
    }

    private lateinit var store: AssistantDraftStore
    private lateinit var views: AssistantDraftViews
    private lateinit var materialService: MaterialService
    private lateinit var trainingEventService: TrainingEventService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var activeCalendarService: ActiveCalendarService
    private lateinit var service: AssistantDraftServiceImpl

    private val sessionId = UUID.randomUUID()
    private val figureId = UUID.randomUUID()
    private val createdNote = Material().apply { id = UUID.randomUUID() }
    private val card = DraftView(UUID.randomUUID(), DraftKind.NOTE, DraftStatus.SAVED, "t", emptyList(), emptyList(), null, null)

    @BeforeEach
    fun setUp() {
        store = mock(AssistantDraftStore::class.java)
        views = mock(AssistantDraftViews::class.java)
        materialService = mock(MaterialService::class.java)
        trainingEventService = mock(TrainingEventService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        activeCalendarService = mock(ActiveCalendarService::class.java)
        service = AssistantDraftServiceImpl(
            store, views, codec, materialService, trainingEventService, danceFigureService, activeCalendarService,
            TransactionTemplate(noTransactions)
        )
    }

    private fun claimed(kind: DraftKind, request: Any): AssistantDraft {
        val draft = AssistantDraft().apply { id = UUID.randomUUID(); this.kind = kind; payload = codec.toPayload(request); status = DraftStatus.SAVED }
        `when`(store.claim(draft.id!!)).thenReturn(draft)
        `when`(store.findOwned(draft.id!!)).thenReturn(draft)
        `when`(views.build(draft)).thenReturn(card)
        return draft
    }

    private fun noteRequest(session: UUID? = sessionId, attended: Boolean = true, figures: List<UUID> = listOf(figureId)) =
        MaterialRequest(name = "Tuesday class", version = 0, trainingEventId = session, figureIds = figures, markAttended = attended)

    /** Whether [method] was called on [mock] with any arguments (Mockito's `any()` does not work with non-null Kotlin parameters). */
    private fun called(mock: Any, method: String) = Mockito.mockingDetails(mock).invocations.any { it.method.name == method }

    // ── notes ──────────────────────────────────────────────────────────────

    @Test
    fun `saving a note calls the form's services in order - create, pin, attendance, then the session link`() {
        val request = noteRequest()
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        `when`(trainingEventService.findById(sessionId)).thenReturn(TrainingEvent().apply { id = sessionId; materialsUrl = "https://example.com/h" })
        `when`(trainingEventService.bulkUpdateMaterial(listOf(sessionId), createdNote.id, "https://example.com/h"))
            .thenReturn(BulkEditResult(updatedCount = 1))

        service.save(draft.id!!)

        val order: InOrder = inOrder(store, materialService, trainingEventService)
        order.verify(store).claim(draft.id!!)
        order.verify(materialService).create(request)
        order.verify(materialService).addFigure(createdNote.id!!, FigureRequest(danceFigureId = figureId))
        order.verify(trainingEventService).updateAttendance(sessionId, AttendanceStatus.ATTENDED)
        order.verify(trainingEventService).bulkUpdateMaterial(listOf(sessionId), createdNote.id, "https://example.com/h")
        order.verify(store).finish(draft.id!!, createdNote.id!!, null)
    }

    @Test
    fun `a note without markAttended leaves attendance alone`() {
        val request = noteRequest(attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        `when`(trainingEventService.findById(sessionId)).thenReturn(TrainingEvent().apply { id = sessionId })
        `when`(trainingEventService.bulkUpdateMaterial(listOf(sessionId), createdNote.id, null)).thenReturn(BulkEditResult(updatedCount = 1))

        service.save(draft.id!!)

        verify(trainingEventService, never()).updateAttendance(sessionId, AttendanceStatus.ATTENDED)
        verify(materialService, never()).addFigure(createdNote.id!!, FigureRequest(danceFigureId = figureId))
    }

    @Test
    fun `a note with no session is saved and nothing is linked`() {
        val request = noteRequest(session = null, attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, createdNote.id!!, null)
        verify(trainingEventService, never()).findById(sessionId)
    }

    @Test
    fun `a link that fails keeps the saved note and says so on the card, like the form does`() {
        val request = noteRequest(attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        `when`(trainingEventService.findById(sessionId)).thenReturn(TrainingEvent().apply { id = sessionId })
        `when`(trainingEventService.bulkUpdateMaterial(listOf(sessionId), createdNote.id, null))
            .thenReturn(BulkEditResult(updatedCount = 0, failedCount = 1))

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, createdNote.id!!, AssistantDraftServiceImpl.LINK_FAILED)
        assertFalse(called(store, "release"), "the note exists, so the draft must not go back to pending")
    }

    @Test
    fun `a finish that fails after the entity exists never reopens the draft, so Save cannot make a duplicate`() {
        val request = noteRequest(attended = false, figures = emptyList(), session = null)
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        doThrow(IllegalStateException("connection lost")).`when`(store).finish(draft.id!!, createdNote.id!!, null)

        service.save(draft.id!!)

        assertFalse(called(store, "release"), "the note exists; releasing would offer Save again")
    }

    @Test
    fun `a failure before the note exists releases the draft with the error`() {
        val request = noteRequest(attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenThrow(IllegalArgumentException("Name is too short"))

        service.save(draft.id!!)

        verify(store).release(draft.id!!, "Name is too short")
        assertFalse(called(store, "finish"), "a failed save must not record a created id")
    }

    @Test
    fun `an attendance the user may not record releases the draft, and the note is not linked`() {
        val request = noteRequest(figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        doThrow(org.springframework.security.access.AccessDeniedException("Only calendar members can record attendance."))
            .`when`(trainingEventService).updateAttendance(sessionId, AttendanceStatus.ATTENDED)

        service.save(draft.id!!)

        verify(store).release(draft.id!!, "Only calendar members can record attendance.")
        verify(trainingEventService, never()).bulkUpdateMaterial(listOf(sessionId), createdNote.id, null)
    }

    @Test
    fun `an unexpected failure shows a generic message, never the exception text`() {
        val request = noteRequest(attended = false, figures = emptyList(), session = null)
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenThrow(IllegalStateException("could not execute statement; SQL [insert into material ...]"))

        service.save(draft.id!!)

        verify(store).release(draft.id!!, AssistantDraftServiceImpl.GENERIC_FAILURE)
    }

    // ── sessions and figures ───────────────────────────────────────────────

    @Test
    fun `saving a session validates the calendar like the form and creates it there`() {
        val calendarId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0),
            calendarId = calendarId
        )
        val draft = claimed(DraftKind.TRAINING_EVENT, request)
        val target = TrainingCalendar().apply { id = calendarId }
        `when`(activeCalendarService.validateCreationTarget(calendarId)).thenReturn(target)
        val created = TrainingEvent().apply { id = UUID.randomUUID() }
        `when`(trainingEventService.create(request.copy(calendarId = calendarId))).thenReturn(created)

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, created.id!!, null)
    }

    @Test
    fun `a calendar sync failure on Save leaves the draft pending with the error`() {
        val calendarId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0),
            calendarId = calendarId
        )
        val draft = claimed(DraftKind.TRAINING_EVENT, request)
        `when`(activeCalendarService.validateCreationTarget(calendarId)).thenReturn(TrainingCalendar().apply { id = calendarId })
        `when`(trainingEventService.create(request.copy(calendarId = calendarId))).thenThrow(CalendarSyncException("Google Calendar is unavailable"))

        service.save(draft.id!!)

        verify(store).release(draft.id!!, "Google Calendar is unavailable")
        assertFalse(called(store, "finish"))
    }

    @Test
    fun `saving a figure calls the figure service`() {
        val request = DanceFigureRequest(name = "Heel Turn", danceTypeId = UUID.randomUUID())
        val draft = claimed(DraftKind.FIGURE, request)
        val created = DanceFigure().apply { id = UUID.randomUUID() }
        `when`(danceFigureService.create(request)).thenReturn(created)

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, created.id!!, null)
    }

    // ── refusals ───────────────────────────────────────────────────────────

    @Test
    fun `a draft that is not pending writes nothing, and the refusal reaches the caller`() {
        val id = UUID.randomUUID()
        `when`(store.claim(id)).thenThrow(DraftNotPendingException(DraftStatus.SAVED))

        assertThrows(DraftNotPendingException::class.java) { service.save(id) }

        assertFalse(called(materialService, "create"))
        assertFalse(called(trainingEventService, "create"))
        assertFalse(called(danceFigureService, "create"))
    }

    @Test
    fun `someone else's draft is a 404 before anything is written`() {
        val id = UUID.randomUUID()
        `when`(store.claim(id)).thenThrow(EntityNotFoundException("Draft not found"))

        assertThrows(EntityNotFoundException::class.java) { service.save(id) }
    }

    // ── edit in form ───────────────────────────────────────────────────────

    @Test
    fun `edit in form discards the draft and points at the matching create form`() {
        val note = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.NOTE }
        val event = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.TRAINING_EVENT }
        val figure = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.FIGURE }
        listOf(note, event, figure).forEach { `when`(store.discardForForm(it.id!!)).thenReturn(it) }

        assertEquals("/materials/new?fromDraft=${note.id}", service.openInForm(note.id!!))
        assertEquals("/training-events/new?fromDraft=${event.id}", service.openInForm(event.id!!))
        assertEquals("/dance-figures/new?fromDraft=${figure.id}", service.openInForm(figure.id!!))
    }

    @Test
    fun `the forms read their draft as the request they bind, or null`() {
        val request = noteRequest()
        val draft = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.NOTE; payload = codec.toPayload(request) }
        `when`(store.findOpenOrNull(draft.id!!, DraftKind.NOTE)).thenReturn(draft)

        assertEquals(request, service.noteForForm(draft.id!!))
        assertNull(service.noteForForm(UUID.randomUUID()))
        assertNull(service.sessionForForm(draft.id!!))
        assertNull(service.figureForForm(draft.id!!))
    }
}
