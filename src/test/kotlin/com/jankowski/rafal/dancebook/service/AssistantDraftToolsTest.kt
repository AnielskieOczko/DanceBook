package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftToolsTest {

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private val mapper = DraftTestSupport.mapper()
    /** Drafts are read back the way production reads them: the codec ignores computed properties like `isRepeating`. */
    private val codec = AssistantDraftCodec(mapper)
    private val standard = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val waltz = DanceType().apply { id = UUID.randomUUID(); name = "Waltz"; category = standard }
    private val calendar = TrainingCalendar().apply { id = UUID.randomUUID() }

    private val figureId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val noteId = UUID.randomUUID()

    private val turns = AssistantTurnScope()
    private lateinit var conversations: FakeAssistantConversationService
    private lateinit var drafts: FakeAssistantDraftService
    private lateinit var tools: AssistantDraftTools
    private lateinit var turn: ToolTurn
    private lateinit var activeCalendarService: ActiveCalendarService

    @BeforeEach
    fun setUp() {
        conversations = FakeAssistantConversationService(user)
        drafts = FakeAssistantDraftService()
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        val danceCategoryService = mock(DanceCategoryService::class.java)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(standard))
        activeCalendarService = mock(ActiveCalendarService::class.java)
        `when`(activeCalendarService.creationTarget()).thenReturn(calendar)
        tools = AssistantDraftTools(
            turns, AssistantGrounding(conversations, mapper), drafts, AssistantDraftCodec(mapper),
            DraftTestSupport.validator(), danceTypeService, danceCategoryService, activeCalendarService
        )
        turn = ToolTurn(conversations.start("wrap up").id!!, ResolvedPage(PageContextType.HOME, null, null))
    }

    private fun ground(kind: String, id: UUID) {
        conversations.append(
            turn.conversationId, AssistantRole.TOOL, "search",
            mutableMapOf(
                "name" to "search",
                "result" to mutableMapOf(
                    "total" to 1,
                    "items" to listOf(mutableMapOf<String, Any?>("kind" to kind, "id" to id.toString(), "title" to "t", "url" to "/x", "chips" to emptyList<String>()))
                )
            )
        )
    }

    private fun <T> inTurn(block: () -> T): T = turns.run(turn, block)

    private fun note(
        title: String = "Tuesday class", text: String? = "Feather step. Head drops.", style: String? = "Waltz",
        figures: List<String>? = listOf(figureId.toString()), session: String? = sessionId.toString(),
        attended: Boolean? = true, video: String? = null
    ): ToolResult = inTurn { tools.draftNote(title, text, style, figures, session, attended, video) }

    // ── draft_note ─────────────────────────────────────────────────────────

    @Test
    fun `a note draft with grounded ids is stored as a pending draft and nothing else happens`() {
        ground("figure", figureId)
        ground("session", sessionId)

        val result = note()

        val created = drafts.created.single()
        assertEquals(DraftKind.NOTE, created.kind)
        assertEquals(created.id.toString(), result.draftId)
        val request = mapper.convertValue(created.payload, MaterialRequest::class.java)
        assertEquals("Tuesday class", request.name)
        assertEquals("<div>Feather step. Head drops.</div>", request.description)
        assertEquals(waltz.id, request.danceTypeId)
        assertEquals(standard.id, request.danceCategoryId)
        assertEquals(listOf(figureId), request.figureIds)
        assertEquals(sessionId, request.trainingEventId)
        assertTrue(request.markAttended)
        assertNull(result.message?.takeIf { it.contains("Left out") })
    }

    @Test
    fun `an id the model invented is dropped, the rest of the draft is kept, and the model is told`() {
        ground("figure", figureId)
        val invented = UUID.randomUUID()

        val result = note(figures = listOf(figureId.toString(), invented.toString()), session = null, attended = false)

        val request = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java)
        assertEquals(listOf(figureId), request.figureIds)
        assertTrue(result.message!!.contains("Left out"), result.message)
    }

    @Test
    fun `a figure id that is not even a uuid is dropped, not fatal`() {
        val result = note(figures = listOf("natural-turn"), session = null, attended = false)

        val request = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java)
        assertTrue(request.figureIds.isEmpty())
        assertNotNull(result.draftId)
        assertTrue(result.message!!.contains("natural-turn"), result.message)
    }

    @Test
    fun `a session id that no tool returned is dropped, and markAttended goes with it`() {
        val result = note(session = UUID.randomUUID().toString(), figures = null)

        val request = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java)
        assertNull(request.trainingEventId)
        assertFalse(request.markAttended)
        assertTrue(result.message!!.contains("markAttended"), result.message)
    }

    @Test
    fun `an id from the same round as its search is not stored yet, so it is dropped`() {
        // The search result is stored after the round ends, so a draft in that round cannot cite it.
        val result = note(figures = listOf(figureId.toString()), session = null, attended = false)

        assertTrue(mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).figureIds.isEmpty())
        assertTrue(result.message!!.contains("tool result"), result.message)
    }

    @Test
    fun `the session the user is looking at counts as grounded`() {
        turn = ToolTurn(turn.conversationId, ResolvedPage(PageContextType.SESSION, sessionId, "Standard group class"))

        note(figures = null)

        assertEquals(sessionId, mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).trainingEventId)
    }

    @Test
    fun `a title that is too short gets no draft and one chance to fix it, then an instruction to ask the user`() {
        val first = note(title = "x", session = null, figures = null, attended = false)
        assertNull(first.draftId)
        assertTrue(first.message!!.contains("once more"), first.message)

        val second = note(title = "y", session = null, figures = null, attended = false)
        assertNull(second.draftId)
        assertTrue(second.message!!.contains("Do not call draft_note again"), second.message)
        assertTrue(drafts.created.isEmpty())
    }

    @Test
    fun `an unknown dance style is a problem for the model to fix, not a silent drop`() {
        val result = note(style = "Lindy Hop", session = null, figures = null, attended = false)

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("Lindy Hop"), result.message)
    }

    @Test
    fun `markup in the note text is escaped`() {
        note(text = "<script>alert(1)</script>", session = null, figures = null, attended = false)

        val description = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).description!!
        assertFalse(description.contains("<script>"), description)
    }

    @Test
    fun `a tool called outside a turn refuses instead of guessing a conversation`() {
        val result = tools.draftNote("Tuesday class", null, null, null, null, null, null)

        assertNull(result.draftId)
        assertTrue(drafts.created.isEmpty())
    }

    // ── draft_training_event ───────────────────────────────────────────────

    private fun session(
        title: String = "Practice", date: String = "2026-10-03", start: String = "10:00", end: String = "12:00",
        type: String? = null, segments: List<DraftSegmentArg>? = null, description: String? = null, note: String? = null
    ): ToolResult = inTurn { tools.draftTrainingEvent(title, date, start, end, type, segments, description, note) }

    @Test
    fun `a session draft carries the date, times, calendar, styles and a grounded note`() {
        ground("note", noteId)

        val result = session(
            segments = listOf(DraftSegmentArg().apply { category = "standard"; minutes = 60 }),
            description = "Quickstep then tango", note = noteId.toString()
        )

        val created = drafts.created.single()
        assertEquals(DraftKind.TRAINING_EVENT, created.kind)
        assertEquals(created.id.toString(), result.draftId)
        val request = codec.read(created.payload, TrainingEventRequest::class.java)
        assertEquals(LocalDate.of(2026, 10, 3), request.date)
        assertEquals(LocalTime.of(10, 0), request.startTime)
        assertEquals(LocalTime.of(12, 0), request.endTime)
        assertEquals("TRAINING", request.eventType)
        assertEquals(calendar.id, request.calendarId)
        assertEquals(standard.id, request.segments.single().categoryId)
        assertEquals(60, request.segments.single().durationMinutes)
        assertEquals(noteId, request.materialId)
        assertEquals("NONE", request.repeat)
    }

    @Test
    fun `an end time that is not after the start is refused with a reason`() {
        val result = session(start = "12:00", end = "10:00")

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("endTime"), result.message)
    }

    @Test
    fun `an event type outside the enum is refused and the valid ones are listed`() {
        val result = session(type = "PARTY")

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("TRAINING") && result.message!!.contains("CAMP"), result.message)
    }

    @Test
    fun `a date that is not a real day is refused`() {
        val result = session(date = "next Saturday")

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("yyyy-MM-dd"), result.message)
    }

    @Test
    fun `an unknown segment category is refused and the real ones are listed`() {
        val result = session(segments = listOf(DraftSegmentArg().apply { category = "Polka"; minutes = 30 }))

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("Standard"), result.message)
    }

    @Test
    fun `no writable calendar means no draft, and the reason reaches the model without using up its retry`() {
        `when`(activeCalendarService.creationTarget()).thenThrow(CalendarSyncException("The active calendar is disabled"))

        val result = session()

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("disabled"), result.message)
        assertTrue(turn.failures.isEmpty())
    }

    // ── draft_figure ───────────────────────────────────────────────────────

    @Test
    fun `a figure draft needs a known dance style and may carry a class and timing`() {
        val result = inTurn { tools.draftFigure("Heel Turn", "Waltz", "D", "1 2 3", "Facing DW", "Facing LOD", "Turn on the heel") }

        val created = drafts.created.single()
        assertEquals(DraftKind.FIGURE, created.kind)
        assertEquals(created.id.toString(), result.draftId)
        val request = mapper.convertValue(created.payload, DanceFigureRequest::class.java)
        assertEquals("Heel Turn", request.name)
        assertEquals(waltz.id, request.danceTypeId)
        assertEquals(DanceClass.D, request.danceClass)
        assertEquals("1 2 3", request.alternativeTiming)
    }

    @Test
    fun `a figure draft with an unknown style or class is refused`() {
        assertNull(inTurn { tools.draftFigure("Heel Turn", "Lindy Hop", null, null, null, null, null) }.draftId)
        assertNull(inTurn { tools.draftFigure("Heel Turn", "Waltz", "Z", null, null, null, null) }.draftId)
        assertTrue(drafts.created.isEmpty())
    }

    // ── through Spring AI's own argument parsing ───────────────────────────

    @Test
    fun `the session tool is callable with the JSON a model sends, nested segments included`() {
        val callback = tools.callbacks.first { it.toolDefinition.name() == "draft_training_event" }
        assertTrue(callback.toolDefinition.inputSchema().contains("minutes"), callback.toolDefinition.inputSchema())

        val json = inTurn {
            callback.call(
                """{"title":"Practice","date":"2026-10-03","startTime":"10:00","endTime":"12:00",""" +
                    """"segments":[{"category":"Standard","minutes":60}]}"""
            )
        }

        assertTrue(json.contains("draftId"), json)
        val request = codec.read(drafts.created.single().payload, TrainingEventRequest::class.java)
        assertEquals(60, request.segments.single().durationMinutes)
    }

    @Test
    fun `all three tools are offered to the model by name`() {
        assertEquals(
            setOf("draft_note", "draft_training_event", "draft_figure"),
            tools.callbacks.map { it.toolDefinition.name() }.toSet()
        )
    }
}
