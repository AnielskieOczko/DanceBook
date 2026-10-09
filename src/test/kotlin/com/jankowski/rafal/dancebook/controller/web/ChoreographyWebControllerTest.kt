package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Choreography
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.ChoreographyService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.ui.ConcurrentModel
import org.springframework.validation.BindingResult
import java.util.UUID

class ChoreographyWebControllerTest {

    private lateinit var choreographyService: ChoreographyService
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var appUserService: AppUserService
    private lateinit var controller: ChoreographyWebController

    private val currentUser = AppUser().apply { id = UUID.randomUUID(); username = "tester" }

    @BeforeEach
    fun setUp() {
        choreographyService = mock(ChoreographyService::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        appUserService = mock(AppUserService::class.java)

        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        controller = ChoreographyWebController(
            choreographyService,
            danceTypeService,
            danceFigureService,
            appUserService
        )
    }

    @Test
    fun `should list all choreographies when no medal level is specified`() {
        val model = ConcurrentModel()
        `when`(choreographyService.findByCurrentUser(null)).thenReturn(emptyList())

        val viewName = controller.listAll(view = "list", medalLevel = null, isHtmxRequest = false, model = model)

        assertEquals("choreographies/index", viewName)
        assertEquals(emptyList<Choreography>(), model["choreographies"])
        assertNull(model["selectedMedalLevel"])
        verify(choreographyService).findByCurrentUser(null)
    }

    @Test
    fun `should pass medal level to service and set selectedMedalLevel in model`() {
        val model = ConcurrentModel()
        val goldChoreo = Choreography().apply {
            id = UUID.randomUUID()
            name = "Gold Routine"
            medalLevel = MedalLevel.GOLD
        }
        `when`(choreographyService.findByCurrentUser(MedalLevel.GOLD)).thenReturn(listOf(goldChoreo))

        val viewName = controller.listAll(view = "list", medalLevel = MedalLevel.GOLD, isHtmxRequest = false, model = model)

        assertEquals("choreographies/index", viewName)
        assertEquals(listOf(goldChoreo), model["choreographies"])
        assertEquals(MedalLevel.GOLD, model["selectedMedalLevel"])
        verify(choreographyService).findByCurrentUser(MedalLevel.GOLD)
    }

    @Test
    fun `should populate medalLevel in metadata form request`() {
        val choreoId = UUID.randomUUID()
        val choreo = Choreography().apply {
            id = choreoId
            name = "Silver Routine"
            danceType = DanceType().apply { id = UUID.randomUUID(); name = "Tango" }
            owner = currentUser
            medalLevel = MedalLevel.SILVER
            isPublic = true
        }
        `when`(choreographyService.findById(choreoId)).thenReturn(choreo)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())

        val model = ConcurrentModel()
        val viewName = controller.showEditMetadataForm(choreoId, model)

        assertEquals("choreographies/form", viewName)
        val formRequest = model["choreographyRequest"] as ChoreographyRequest
        assertEquals("Silver Routine", formRequest.name)
        assertEquals(MedalLevel.SILVER, formRequest.medalLevel)
        assertEquals(true, formRequest.isPublic)
    }

    @Test
    fun `should create choreography with medalLevel`() {
        val model = ConcurrentModel()
        val bindingResult = mock(BindingResult::class.java)
        `when`(bindingResult.hasErrors()).thenReturn(false)

        val request = ChoreographyRequest(
            name = "Bronze Choreo",
            danceTypeId = UUID.randomUUID(),
            medalLevel = MedalLevel.BRONZE
        )
        val created = Choreography().apply {
            id = UUID.randomUUID()
            name = request.name
            medalLevel = request.medalLevel
        }
        `when`(choreographyService.create(request)).thenReturn(created)

        val result = controller.createChoreography(request, bindingResult, model)

        assertEquals("redirect:/choreographies/${created.id}/edit", result)
        verify(choreographyService).create(request)
    }

    @Test
    fun `should update metadata with medalLevel`() {
        val choreoId = UUID.randomUUID()
        val model = ConcurrentModel()
        val bindingResult = mock(BindingResult::class.java)
        `when`(bindingResult.hasErrors()).thenReturn(false)

        val request = ChoreographyRequest(
            name = "Updated Choreo",
            danceTypeId = UUID.randomUUID(),
            medalLevel = MedalLevel.GOLD
        )
        val updated = Choreography().apply {
            id = choreoId
            name = request.name
            medalLevel = request.medalLevel
        }
        `when`(choreographyService.update(choreoId, request)).thenReturn(updated)

        val result = controller.updateMetadata(choreoId, request, bindingResult, model)

        assertEquals("redirect:/choreographies/$choreoId", result)
        verify(choreographyService).update(choreoId, request)
    }
}
