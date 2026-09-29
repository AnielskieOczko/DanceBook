package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class AssistantReadToolsTest {

    private lateinit var materialService: MaterialService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var trainingEventService: TrainingEventService
    private lateinit var appUserService: AppUserService
    private lateinit var tools: AssistantReadTools

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private val waltz = DanceType().apply { id = UUID.randomUUID(); name = "Waltz" }
    private val clock = Clock.fixed(LocalDateTime.of(2026, 9, 29, 10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        materialService = mock(MaterialService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        trainingEventService = mock(TrainingEventService::class.java)
        appUserService = mock(AppUserService::class.java)
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        tools = AssistantReadTools(
            materialService, danceFigureService, danceTypeService, trainingEventService,
            appUserService, RichTextServiceImpl(), clock
        )
    }

    private fun figure(name: String) = DanceFigure().apply {
        id = UUID.randomUUID(); this.name = name; danceType = waltz; danceClass = DanceClass.D; alternativeTiming = "1 2 3"
    }

    private fun note(name: String, text: String?) = Material().apply {
        id = UUID.randomUUID(); this.name = name; description = text; danceType = waltz
        createdAt = LocalDateTime.of(2026, 9, 11, 19, 0)
    }

    @Test
    fun `there are exactly five tools, and the model can see their argument names`() {
        val names = tools.callbacks.map { it.toolDefinition.name() }.sorted()
        assertEquals(listOf("get_figure", "get_note", "list_sessions", "search_figures", "search_notes"), names)
        val schema = tools.callbacks.first { it.toolDefinition.name() == "search_figures" }.toolDefinition.inputSchema()
        assertTrue(schema.contains("query"), schema)
        assertTrue(schema.contains("danceType"), schema)
    }

    @Test
    fun `search_figures asks the catalog service and caps at ten`() {
        val many = (1..14).map { figure("Heel Turn $it") }
        `when`(danceFigureService.findAll(null, null, null, "heel turn", null, null)).thenReturn(many)

        val result = tools.searchFigures("heel turn", null, null)

        assertEquals(14, result.total)
        assertEquals(10, result.items.size)
        val first = result.items.first()
        assertEquals("figure", first.kind)
        assertEquals("/dance-figures/${many[0].id}", first.url)
        assertEquals("Waltz · Class D", first.subtitle)
        assertEquals(listOf("1 2 3"), first.chips)
    }

    @Test
    fun `search_figures resolves the style name and the class letter`() {
        `when`(danceFigureService.findAll(listOf(waltz.id!!), null, DanceClass.D, "turn", null, null))
            .thenReturn(listOf(figure("Natural Turn")))

        val result = tools.searchFigures("turn", "waltz", "d")

        assertEquals(1, result.total)
    }

    @Test
    fun `an unknown style or class is reported, not silently ignored`() {
        val style = tools.searchFigures("turn", "Polka", null)
        assertEquals(0, style.items.size)
        assertTrue(style.message!!.contains("Polka"))

        val cls = tools.searchFigures("turn", null, "Z")
        assertEquals(0, cls.items.size)
        assertTrue(cls.message!!.contains("Z"))
    }

    @Test
    fun `search_notes goes through the material service and builds linked cards with a snippet`() {
        val n = note("Rise and fall", "There is no sway on step one, then sway to the right on two and three.")
        n.figures.add(Figure().apply { danceFigure = figure("Natural Turn") })
        `when`(materialService.searchNotes("sway", null, null, 10)).thenReturn(listOf(n))

        val result = tools.searchNotes("sway", null, null)

        assertEquals(1, result.items.size)
        val card = result.items.first()
        assertEquals("note", card.kind)
        assertEquals("/materials/${n.id}", card.url)
        assertTrue(card.snippet!!.contains("sway"))
        assertEquals("Waltz · 11 Sep 2026", card.subtitle)
        assertEquals(listOf("Natural Turn · 1 2 3"), card.chips)
    }

    @Test
    fun `search_notes passes the figure and style filters`() {
        val figureId = UUID.randomUUID()
        `when`(materialService.searchNotes(null, figureId, waltz.id, 10)).thenReturn(emptyList())
        val result = tools.searchNotes(null, figureId.toString(), "Waltz")
        assertEquals(0, result.total)
        assertNull(result.message)
    }

    @Test
    fun `list_sessions filters by date window and unconfirmed`() {
        val past = TrainingEvent().apply {
            id = UUID.randomUUID(); title = "Standard class"
            startTime = LocalDateTime.of(2026, 9, 23, 19, 0); endTime = LocalDateTime.of(2026, 9, 23, 20, 30)
            segments.add(TrainingEventSegment().apply {
                danceCategory = DanceCategory().apply { name = "Standard" }; durationMinutes = 45
            })
        }
        val future = TrainingEvent().apply {
            id = UUID.randomUUID(); title = "Practice"
            startTime = LocalDateTime.of(2026, 10, 1, 10, 0); endTime = LocalDateTime.of(2026, 10, 1, 12, 0)
        }
        `when`(trainingEventService.findInRange(
            LocalDateTime.of(2026, 9, 21, 0, 0), LocalDateTime.of(2026, 10, 5, 0, 0), null
        )).thenReturn(listOf(future, past))

        val all = tools.listSessions("2026-09-21", "2026-10-04", false)
        assertEquals(listOf("Standard class", "Practice"), all.items.map { it.title })
        assertEquals("/training-events/${past.id}", all.items.first().url)
        assertEquals("Wed 23 Sep · 19:00–20:30", all.items.first().subtitle)
        assertEquals(listOf("Standard 45m", "To confirm"), all.items.first().chips)

        // `past` ended before the fixed clock (2026-09-29 10:00) and has no attendance row, so it is
        // PLANNED and unconfirmed (TrainingEvent.isAwaitingConfirmationFor); `future` has not happened yet.
        val unconfirmed = tools.listSessions("2026-09-21", "2026-10-04", true)
        assertEquals(listOf("Standard class"), unconfirmed.items.map { it.title })
    }

    @Test
    fun `list_sessions with a bad date returns a message instead of throwing`() {
        val result = tools.listSessions("next tuesday", "2026-10-04", null)
        assertEquals(0, result.items.size)
        assertTrue(result.message!!.contains("yyyy-MM-dd"))
    }

    @Test
    fun `get_note and get_figure return one card, and bad or hidden ids are a message`() {
        val n = note("Rise and fall", "sway")
        val f = figure("Natural Turn")
        `when`(materialService.findById(n.id!!)).thenReturn(n)
        `when`(danceFigureService.findById(f.id!!)).thenReturn(f)
        val hidden = UUID.randomUUID()
        `when`(materialService.findById(hidden)).thenThrow(EntityNotFoundException("hidden"))

        assertEquals(1, tools.getNote(n.id.toString()).items.size)
        assertEquals(1, tools.getFigure(f.id.toString()).items.size)
        assertTrue(tools.getNote("not-a-uuid").message!!.contains("id"))
        assertNotNull(tools.getNote(hidden.toString()).message)
        assertEquals(0, tools.getNote(hidden.toString()).items.size)
    }
}
