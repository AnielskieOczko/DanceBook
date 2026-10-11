package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.ui.ConcurrentModel
import java.util.UUID

class DanceFigureWebControllerTest {

    private lateinit var danceFigureService: DanceFigureService
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var controller: DanceFigureWebController

    @BeforeEach
    fun setUp() {
        danceFigureService = mock(DanceFigureService::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        controller = DanceFigureWebController(danceFigureService, danceTypeService, danceCategoryService)
    }

    @Test
    fun `should list all figures when no dance type is specified`() {
        val model = ConcurrentModel()
        `when`(danceFigureService.findAll(null, null, null, null, null)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.listDanceFigures(typeIds = null, model = model)

        assertEquals("dance-figures/list", viewName)
        assertEquals(emptyList<DanceFigure>(), model["figures"])
        assertEquals(emptyList<DanceType>(), model["danceTypes"])
        assertEquals(emptyList<UUID>(), model["selectedTypeIds"])
    }

    @Test
    fun `should list filtered figures when dance type is specified`() {
        val model = ConcurrentModel()
        val danceTypeId = UUID.randomUUID()
        val typeIds = listOf(danceTypeId)
        `when`(danceFigureService.findAll(typeIds, null, null, null, null)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.listDanceFigures(typeIds = typeIds, model = model)

        assertEquals("dance-figures/list", viewName)
        assertEquals(emptyList<DanceFigure>(), model["figures"])
        assertEquals(emptyList<DanceType>(), model["danceTypes"])
        assertEquals(typeIds, model["selectedTypeIds"])
        verify(danceFigureService).findAll(typeIds, null, null, null, null)
    }

    @Test
    fun `should list figures with hasSteps filter when specified`() {
        val model = ConcurrentModel()
        `when`(danceFigureService.findAll(null, null, null, null, null, true)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.listDanceFigures(hasSteps = true, model = model)

        assertEquals("dance-figures/list", viewName)
        assertEquals(emptyList<DanceFigure>(), model["figures"])
        assertEquals(true, model["selectedHasSteps"])
        verify(danceFigureService).findAll(null, null, null, null, null, true)
    }

    @Test
    fun `should populate figuresWithSteps in model from service`() {
        val model = ConcurrentModel()
        val figureId1 = UUID.randomUUID()
        val figureId2 = UUID.randomUUID()
        val figure1 = DanceFigure().apply { id = figureId1; name = "Figure 1" }
        val figure2 = DanceFigure().apply { id = figureId2; name = "Figure 2" }
        val figureList = listOf(figure1, figure2)

        `when`(danceFigureService.findAll(null, null, null, null, null, null)).thenReturn(figureList)
        `when`(danceFigureService.findFigureIdsWithSteps(listOf(figureId1, figureId2))).thenReturn(setOf(figureId1))
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.listDanceFigures(model = model)

        assertEquals("dance-figures/list", viewName)
        assertEquals(figureList, model["figures"])
        assertEquals(setOf(figureId1), model["figuresWithSteps"])
        assertFalse(model.containsAttribute("figureIdsWithSteps"))
        verify(danceFigureService).findFigureIdsWithSteps(listOf(figureId1, figureId2))
    }

    @Test
    fun `should pass the medal level filter to the catalog query`() {
        val model = ConcurrentModel()
        `when`(danceFigureService.findAll(null, null, null, null, null, null, MedalLevel.GOLD)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        controller.listDanceFigures(medalLevel = MedalLevel.GOLD, model = model)

        verify(danceFigureService).findAll(null, null, null, null, null, null, MedalLevel.GOLD)
        assertEquals(MedalLevel.GOLD, model["selectedMedalLevel"])
    }

    @Test
    fun `should show edit form with all fields mapped`() {
        val model = ConcurrentModel()
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val danceFigure = DanceFigure().apply {
            id = figureId
            name = "Back Whisk"
            this.danceType = danceType
            danceClass = DanceClass.H
            medalLevel = MedalLevel.BRONZE
            alternativeTiming = "123&"
            startingPosition = "Closed"
            endingPosition = "Promenade"
            precedingFigureNames = listOf("Fig A")
            followingFigureNames = listOf("Fig B")
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(danceFigure)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.showEditForm(figureId, model)

        assertEquals("dance-figures/form", viewName)
        assertEquals(figureId, model["danceFigureId"])
        
        val request = model["danceFigure"] as DanceFigureRequest
        assertNotNull(request)
        assertEquals("Back Whisk", request.name)
        assertEquals(danceType.id, request.danceTypeId)
        assertEquals(DanceClass.H, request.danceClass)
        assertEquals(MedalLevel.BRONZE, request.medalLevel)
        assertEquals("123&", request.alternativeTiming)
        assertEquals("Closed", request.startingPosition)
        assertEquals("Promenade", request.endingPosition)
        assertEquals(listOf("Fig A"), request.precedingFigureNames)
        assertEquals(listOf("Fig B"), request.followingFigureNames)
    }

    @Test
    fun `should show details page with matching name mapping`() {
        val model = ConcurrentModel()
        val figureId = UUID.randomUUID()
        val otherFigureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val danceFigure = DanceFigure().apply {
            id = figureId
            name = "Back Whisk"
            this.danceType = danceType
            precedingFigureNames = listOf("Natural Spin Turn")
        }
        val otherFigure = DanceFigure().apply {
            id = otherFigureId
            name = "Natural Spin Turn"
            this.danceType = danceType
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(danceFigure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(danceFigure, otherFigure))

        val viewName = controller.showDetails(figureId, model)

        assertEquals("dance-figures/view", viewName)
        assertEquals(danceFigure, model["danceFigure"])
        
        val figureNameMap = model["figureNameMap"] as Map<*, *>
        assertNotNull(figureNameMap)
        assertEquals(figureId, figureNameMap["Back Whisk"])
        assertEquals(otherFigureId, figureNameMap["Natural Spin Turn"])
    }

    @Test
    fun `the figure form opens prefilled from an assistant draft`() {
        val draftId = UUID.randomUUID()
        val drafts = mock(AssistantDraftService::class.java)
        val typeId = UUID.randomUUID()
        `when`(danceFigureService.findByDanceType(typeId)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(drafts.figureForForm(draftId)).thenReturn(
            DanceFigureRequest(name = "Heel Turn", danceTypeId = typeId, alternativeTiming = "1 2 3")
        )
        controller.assistantDrafts = drafts
        val model = ConcurrentModel()

        val view = controller.showCreateForm(null, draftId, model)

        assertEquals("dance-figures/form", view)
        val form = model.getAttribute("danceFigure") as DanceFigureRequest
        assertEquals("Heel Turn", form.name)
        assertEquals("1 2 3", form.alternativeTiming)
    }

    @Test
    fun `a missing or foreign figure draft opens the blank form`() {
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        val drafts = mock(AssistantDraftService::class.java)
        controller.assistantDrafts = drafts
        val model = ConcurrentModel()

        controller.showCreateForm(null, UUID.randomUUID(), model)

        assertEquals("", (model.getAttribute("danceFigure") as DanceFigureRequest).name)
    }

    @Test
    fun `should populate medal levels and map step sets and links on edit form`() {
        val model = ConcurrentModel()
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Rumba"
        }
        val set = com.jankowski.rafal.dancebook.model.DanceFigureStepSet().apply {
            id = UUID.randomUUID()
            name = "Basic Movement"
            isDefault = true
            steps = mutableListOf(
                com.jankowski.rafal.dancebook.model.DanceFigureStep().apply {
                    id = UUID.randomUUID()
                    stepNumber = 1
                    role = "LEADER"
                    timing = "2"
                    foot = "LF"
                    action = "Forward"
                },
                com.jankowski.rafal.dancebook.model.DanceFigureStep().apply {
                    id = UUID.randomUUID()
                    stepNumber = 1
                    role = "FOLLOWER"
                    timing = "2"
                    foot = "RF"
                    action = "Back"
                }
            )
        }
        val link = com.jankowski.rafal.dancebook.model.DanceFigureLink().apply {
            id = UUID.randomUUID()
            title = "Video Demo"
            url = "https://example.com/rumba"
            type = "video"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Rumba Basic"
            this.danceType = danceType
            danceClass = DanceClass.E
            medalLevel = MedalLevel.SILVER
            stepSets = mutableListOf(set)
            links = mutableListOf(link)
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))

        val viewName = controller.showEditForm(figureId, model)

        assertEquals("dance-figures/form", viewName)
        assertEquals(MedalLevel.values().toList(), (model["medalLevels"] as Array<*>).toList())
        assertEquals(DanceClass.values().toList(), (model["danceClasses"] as Array<*>).toList())

        val request = model["danceFigure"] as DanceFigureRequest
        assertEquals("Rumba Basic", request.name)
        assertEquals(MedalLevel.SILVER, request.medalLevel)
        assertEquals(1, request.stepSets.size)
        assertEquals("Basic Movement", request.stepSets[0].name)
        assertEquals(true, request.stepSets[0].isDefault)
        assertEquals(2, request.stepSets[0].steps.size)
        assertEquals("LEADER", request.stepSets[0].steps[0].role)
        assertEquals("FOLLOWER", request.stepSets[0].steps[1].role)

        assertEquals(1, request.links.size)
        assertEquals("Video Demo", request.links[0].title)
        assertEquals("https://example.com/rumba", request.links[0].url)
    }
}
