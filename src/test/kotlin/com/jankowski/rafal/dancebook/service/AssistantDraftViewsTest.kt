package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftViewsTest {

    private val mapper = DraftTestSupport.mapper()
    private val codec = AssistantDraftCodec(mapper)
    private val standard = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val waltz = DanceType().apply { id = UUID.randomUUID(); name = "Waltz"; category = standard }
    private val figure = DanceFigure().apply { id = UUID.randomUUID(); name = "Feather Step"; alternativeTiming = "S Q Q" }
    private val session = TrainingEvent().apply { id = UUID.randomUUID(); title = "Standard group class" }

    private lateinit var views: AssistantDraftViews
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var trainingEventService: TrainingEventService

    @BeforeEach
    fun setUp() {
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findById(waltz.id!!)).thenReturn(waltz)
        val danceCategoryService = mock(DanceCategoryService::class.java)
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)
        danceFigureService = mock(DanceFigureService::class.java)
        `when`(danceFigureService.findById(figure.id!!)).thenReturn(figure)
        trainingEventService = mock(TrainingEventService::class.java)
        `when`(trainingEventService.findById(session.id!!)).thenReturn(session)
        views = AssistantDraftViews(
            codec, RichTextServiceImpl(), danceTypeService, danceCategoryService, danceFigureService,
            mock(MaterialService::class.java), trainingEventService
        )
    }

    private fun draft(kind: DraftKind, request: Any, status: DraftStatus = DraftStatus.PENDING) = AssistantDraft().apply {
        id = UUID.randomUUID(); this.kind = kind; payload = codec.toPayload(request); this.status = status
    }

    @Test
    fun `a note card shows every field, the session, the attendance, and each figure with its timing`() {
        val view = views.build(draft(DraftKind.NOTE, MaterialRequest(
            name = "Tuesday class", description = "<div>Head drops.</div>", danceTypeId = waltz.id, version = 0,
            trainingEventId = session.id, figureIds = listOf(figure.id!!), markAttended = true
        )))

        assertEquals("Tuesday class", view.heading)
        val fields = view.fields.associate { it.label to it.value }
        assertEquals("Head drops.", fields["Text"])
        assertEquals("Waltz", fields["Dance style"])
        assertEquals("Standard group class", fields["Session"])
        assertEquals("Mark the session as attended", fields["Attendance"])
        assertEquals("Feather Step", view.figures.single().name)
        assertEquals("S Q Q", view.figures.single().timing)
        assertEquals("/dance-figures/${figure.id}", view.figures.single().url)
    }

    @Test
    fun `a figure the viewer can no longer see is left off the card rather than failing it`() {
        val gone = UUID.randomUUID()
        `when`(danceFigureService.findById(gone)).thenThrow(EntityNotFoundException("gone"))

        val view = views.build(draft(DraftKind.NOTE, MaterialRequest(name = "Tuesday class", version = 0, figureIds = listOf(gone, figure.id!!))))

        assertEquals(listOf("Feather Step"), view.figures.map { it.name })
    }

    @Test
    fun `a session card shows the day, the time, the style split and the type`() {
        val view = views.build(draft(DraftKind.TRAINING_EVENT, TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0),
            segments = mutableListOf(TrainingEventSegmentRequest(standard.id, 60)), description = "<div>Quickstep then tango</div>"
        )))

        assertEquals("Practice", view.heading)
        val fields = view.fields.associate { it.label to it.value }
        assertEquals("Sat 3 Oct 2026", fields["Date"])
        assertEquals("10:00–12:00", fields["Time"])
        assertEquals("Training", fields["Type"])
        assertEquals("Standard 60m", fields["Styles"])
        assertEquals("Quickstep then tango", fields["Notes"])
    }

    @Test
    fun `a figure card shows its style, class and timing, and skips what is empty`() {
        val view = views.build(draft(DraftKind.FIGURE, DanceFigureRequest(
            name = "Heel Turn", danceTypeId = waltz.id, danceClass = DanceClass.D, alternativeTiming = "1 2 3"
        )))

        val fields = view.fields.associate { it.label to it.value }
        assertEquals("Waltz", fields["Dance style"])
        assertTrue(fields["Class"]!!.contains("D"), fields.toString())
        assertEquals("1 2 3", fields["Timing"])
        assertTrue("Notes" !in fields)
    }

    @Test
    fun `a saved draft links to what it created, for each kind, and a pending one does not`() {
        val created = UUID.randomUUID()
        val note = draft(DraftKind.NOTE, MaterialRequest(name = "x y", version = 0), DraftStatus.SAVED).apply { savedEntityId = created }
        val event = draft(DraftKind.TRAINING_EVENT, TrainingEventRequest(title = "x"), DraftStatus.SAVED).apply { savedEntityId = created }
        val fig = draft(DraftKind.FIGURE, DanceFigureRequest(name = "x"), DraftStatus.SAVED).apply { savedEntityId = created }

        assertEquals("/materials/$created", views.build(note).savedUrl)
        assertEquals("/training-events/$created", views.build(event).savedUrl)
        assertEquals("/dance-figures/$created", views.build(fig).savedUrl)
        assertNull(views.build(draft(DraftKind.FIGURE, DanceFigureRequest(name = "x"))).savedUrl)
    }

    @Test
    fun `the notice travels to the card`() {
        val failed = draft(DraftKind.FIGURE, DanceFigureRequest(name = "x")).apply { notice = "Name is too short" }

        assertEquals("Name is too short", views.build(failed).notice)
    }
}
