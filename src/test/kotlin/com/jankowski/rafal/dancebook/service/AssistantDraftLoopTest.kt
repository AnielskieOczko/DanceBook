package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.model.tool.ToolCallingManager
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The whole loop with a scripted model that drafts: the tools, the grounding and the stored messages together. */
class AssistantDraftLoopTest {

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private val mapper = DraftTestSupport.mapper()
    private val clock = Clock.fixed(LocalDateTime.of(2026, 9, 30, 10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val figure = DanceFigure().apply {
        id = UUID.randomUUID(); name = "Feather Step"; danceType = DanceType().apply { name = "Foxtrot" }; alternativeTiming = "S Q Q"
    }

    private lateinit var conversations: FakeAssistantConversationService
    private lateinit var drafts: FakeAssistantDraftService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var materialService: MaterialService
    private lateinit var trainingEventService: TrainingEventService
    private val turns = AssistantTurnScope()

    @BeforeEach
    fun setUp() {
        conversations = FakeAssistantConversationService(user)
        drafts = FakeAssistantDraftService()
        danceFigureService = mock(DanceFigureService::class.java)
        `when`(danceFigureService.findAll(null, null, null, "feather", null, null)).thenReturn(listOf(figure))
        materialService = mock(MaterialService::class.java)
        trainingEventService = mock(TrainingEventService::class.java)
    }

    private fun service(model: ScriptedChatModel): AssistantServiceImpl {
        val appUserService = mock(AppUserService::class.java)
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        val danceCategoryService = mock(DanceCategoryService::class.java)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }))
        val calendars = mock(ActiveCalendarService::class.java)
        `when`(calendars.creationTarget()).thenReturn(TrainingCalendar().apply { id = UUID.randomUUID() })
        val readTools = AssistantReadTools(
            materialService, danceFigureService, danceTypeService, trainingEventService, appUserService, RichTextServiceImpl(), clock
        )
        val draftTools = AssistantDraftTools(
            turns, AssistantGrounding(conversations, mapper), drafts, AssistantDraftCodec(mapper),
            DraftTestSupport.validator(), danceTypeService, danceCategoryService, calendars
        )
        val pageContexts = AssistantPageContextResolver(
            materialService, danceFigureService, trainingEventService, mock(ChoreographyService::class.java)
        )
        return AssistantServiceImpl(
            AssistantModelGateway(model), ToolCallingManager.builder().build(), readTools, draftTools, drafts, turns,
            conversations, pageContexts, appUserService, AssistantRateLimiter(clock), mapper, clock, Duration.ofSeconds(5)
        )
    }

    private val home = PageContext(PageContextType.HOME)

    @Test
    fun `search then draft - the figure found by the search is pinned, and the draft comes back as a card`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("search_figures", """{"query":"feather"}""", id = "c1"),
            ScriptedChatModel.toolCall(
                "draft_note",
                """{"title":"Tuesday class","text":"Head drops.","figureIds":["${figure.id}"]}""", id = "c2"
            ),
            ScriptedChatModel.text("I drafted the note. Check the card, then Save.")
        ))

        val turn = service(model).send(null, "Tuesday's class: feather step, head dropping", home)

        val draft = drafts.created.single()
        val request = mapper.convertValue(draft.payload, MaterialRequest::class.java)
        assertEquals(listOf(figure.id), request.figureIds)
        val reply = turn.messages.last()
        assertEquals("I drafted the note. Check the card, then Save.", reply.text)
        assertEquals(listOf(draft.id), reply.drafts.map { it.id })
        assertEquals("Tuesday class", reply.drafts.single().heading)
        assertEquals(1, reply.cards.size, "the search result card is still shown")
    }

    @Test
    fun `a draft tool never writes - no domain service is asked to create anything`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("search_figures", """{"query":"feather"}""", id = "c1"),
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class","figureIds":["${figure.id}"]}""", id = "c2"),
            ScriptedChatModel.text("Drafted.")
        ))

        service(model).send(null, "note it", home)

        // Whatever the arguments, no write method of any domain service was called.
        val writes = setOf("create", "update", "delete", "addFigure", "updateAttendance", "bulkUpdateMaterial", "removeFigure")
        listOf<Any>(materialService, danceFigureService, trainingEventService).forEach { service ->
            val called = Mockito.mockingDetails(service).invocations.map { it.method.name }.filter { it in writes }
            assertTrue(called.isEmpty(), "a draft tool wrote through $service: $called")
        }
        assertEquals(1, drafts.created.size)
    }

    @Test
    fun `an id the model invents is dropped from the stored draft`() {
        val invented = UUID.randomUUID()
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class","figureIds":["$invented"]}""", id = "c1"),
            ScriptedChatModel.text("Drafted.")
        ))

        service(model).send(null, "note it", home)

        assertTrue(mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).figureIds.isEmpty())
    }

    @Test
    fun `the draft is tied to the tool message that produced it`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_figure", """{"name":"Heel Turn","danceStyle":"Waltz"}""", id = "c1"),
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class"}""", id = "c2"),
            ScriptedChatModel.text("Drafted.")
        ))

        service(model).send(null, "note it", home)

        val toolMessages = conversations.stored.filter { it.role == AssistantRole.TOOL }
        val noteDraft = drafts.created.single()
        assertEquals(toolMessages.last().id, drafts.attached[noteDraft.id])
    }

    @Test
    fun `a validation failure is stored as an ordinary tool message, and the model's retry produces the draft`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_note", """{"title":"x"}""", id = "c1"),
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class"}""", id = "c2"),
            ScriptedChatModel.text("Drafted.")
        ))

        val turn = service(model).send(null, "note it", home)

        assertEquals(1, drafts.created.size)
        assertEquals(1, turn.messages.last().drafts.size)
    }

    @Test
    fun `the model is offered the draft tools and told that writes are only drafts`() {
        val model = ScriptedChatModel(listOf(ScriptedChatModel.text("ok")))

        service(model).send(null, "hello", home)

        val system = model.prompts.first().instructions.filterIsInstance<SystemMessage>().single().text
        assertTrue(system.contains("draft_note"), system)
        assertTrue(system.contains("Save"), system)
        assertFalse(system.contains("cannot create, change or delete anything yet"), system)
        val options = model.prompts.first().options as org.springframework.ai.google.genai.GoogleGenAiChatOptions
        val names = options.toolCallbacks.map { it.toolDefinition.name() }
        assertTrue(names.containsAll(listOf("search_figures", "draft_note", "draft_training_event", "draft_figure")), names.toString())
    }

    @Test
    fun `reopening a conversation shows its drafts under the reply that made them`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class"}""", id = "c1"),
            ScriptedChatModel.text("Drafted.")
        ))
        val svc = service(model)
        val turn = svc.send(null, "note it", home)

        val view = svc.conversation(turn.conversationId!!)

        assertEquals(1, view.messages.last().drafts.size)
        assertEquals(0, view.messages.first().drafts.size)
    }
}
