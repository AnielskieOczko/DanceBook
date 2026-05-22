package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import org.junit.jupiter.api.Assertions.assertEquals
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
    private lateinit var controller: DanceFigureWebController

    @BeforeEach
    fun setUp() {
        danceFigureService = mock(DanceFigureService::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        controller = DanceFigureWebController(danceFigureService, danceTypeService)
    }

    @Test
    fun `should list all figures when no dance type is specified`() {
        val model = ConcurrentModel()
        `when`(danceFigureService.findAll(null, null, null, null)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())

        val viewName = controller.listDanceFigures(danceTypeId = null, model = model)

        assertEquals("dance-figures/list", viewName)
        assertEquals(emptyList<DanceFigure>(), model["figures"])
        assertEquals(emptyList<DanceType>(), model["danceTypes"])
        assertEquals(null, model["selectedDanceTypeId"])
    }

    @Test
    fun `should list filtered figures when dance type is specified`() {
        val model = ConcurrentModel()
        val danceTypeId = UUID.randomUUID()
        `when`(danceFigureService.findAll(danceTypeId, null, null, null)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())

        val viewName = controller.listDanceFigures(danceTypeId = danceTypeId, model = model)

        assertEquals("dance-figures/list", viewName)
        assertEquals(emptyList<DanceFigure>(), model["figures"])
        assertEquals(emptyList<DanceType>(), model["danceTypes"])
        assertEquals(danceTypeId, model["selectedDanceTypeId"])
        verify(danceFigureService).findAll(danceTypeId, null, null, null)
    }
}

