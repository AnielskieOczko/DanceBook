package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
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
}

