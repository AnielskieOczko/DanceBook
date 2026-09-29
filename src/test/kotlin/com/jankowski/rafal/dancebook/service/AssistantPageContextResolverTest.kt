package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class AssistantPageContextResolverTest {

    private lateinit var materialService: MaterialService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var trainingEventService: TrainingEventService
    private lateinit var choreographyService: ChoreographyService
    private lateinit var resolver: AssistantPageContextResolver

    private val id = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        materialService = mock(MaterialService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        trainingEventService = mock(TrainingEventService::class.java)
        choreographyService = mock(ChoreographyService::class.java)
        resolver = AssistantPageContextResolver(materialService, danceFigureService, trainingEventService, choreographyService)
    }

    @Test
    fun `paths map to a page type and id`() {
        assertEquals(PageContext(PageContextType.HOME), resolver.fromPath("/"))
        assertEquals(PageContext(PageContextType.NOTE, id), resolver.fromPath("/materials/$id"))
        assertEquals(PageContext(PageContextType.NOTE, id), resolver.fromPath("/materials/$id/edit"))
        assertEquals(PageContext(PageContextType.FIGURE, id), resolver.fromPath("/dance-figures/$id"))
        assertEquals(PageContext(PageContextType.SESSION, id), resolver.fromPath("/training-events/$id"))
        assertEquals(PageContext(PageContextType.CHOREOGRAPHY, id), resolver.fromPath("/choreographies/$id"))
        assertEquals(PageContext(PageContextType.OTHER), resolver.fromPath("/materials"))
        assertEquals(PageContext(PageContextType.OTHER), resolver.fromPath("/materials/new"))
        assertEquals(PageContext(PageContextType.OTHER), resolver.fromPath("/training-events/calendar"))
    }

    @Test
    fun `the name comes from the service, not from the client`() {
        `when`(danceFigureService.findById(id)).thenReturn(DanceFigure().apply { name = "Natural Turn" })
        val resolved = resolver.resolve(PageContext(PageContextType.FIGURE, id))
        assertEquals("Natural Turn", resolved.name)
        assertEquals("Natural Turn", resolved.label)
    }

    @Test
    fun `each type resolves through its own service`() {
        `when`(materialService.findById(id)).thenReturn(Material().apply { name = "Sway drill" })
        `when`(trainingEventService.findById(id)).thenReturn(TrainingEvent().apply { title = "Standard class" })
        assertEquals("Sway drill", resolver.resolve(PageContext(PageContextType.NOTE, id)).name)
        assertEquals("Standard class", resolver.resolve(PageContext(PageContextType.SESSION, id)).name)
    }

    @Test
    fun `an item the user cannot see resolves to a page with no name`() {
        `when`(materialService.findById(id)).thenThrow(EntityNotFoundException("hidden"))
        val resolved = resolver.resolve(PageContext(PageContextType.NOTE, id))
        assertNull(resolved.name)
        assertEquals("this note", resolved.label)
    }

    @Test
    fun `home and other pages need no lookup`() {
        assertEquals("Home", resolver.resolve(PageContext(PageContextType.HOME)).label)
        assertNull(resolver.resolve(PageContext(PageContextType.OTHER)).label)
    }
}
