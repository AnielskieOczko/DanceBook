package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.MaterialFigureAddedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureDeletedEvent
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.FigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID

class MaterialServiceFigureTest {

    private lateinit var materialRepository: MaterialRepository
    private lateinit var figureRepository: FigureRepository
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var danceFigureRepository: DanceFigureRepository
    private lateinit var googleDriveService: GoogleDriveService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var appUserService: AppUserService
    private lateinit var materialService: MaterialServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        materialRepository = mock(MaterialRepository::class.java)
        figureRepository = mock(FigureRepository::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        danceFigureRepository = mock(DanceFigureRepository::class.java)
        googleDriveService = mock(GoogleDriveService::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        appUserService = mock(AppUserService::class.java)
        
        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        materialService = MaterialServiceImpl(
            materialRepository,
            figureRepository,
            danceTypeService,
            danceFigureRepository,
            googleDriveService,
            eventPublisher,
            appUserService
        )
    }

    @Test
    fun `should link standard figure to material sequence`() {
        val materialId = UUID.randomUUID()
        val danceFigureId = UUID.randomUUID()

        val material = Material().apply {
            id = materialId
            name = "Waltz Sequence"
        }

        val danceFigure = DanceFigure().apply {
            id = danceFigureId
            name = "Natural Turn"
        }

        `when`(materialRepository.findById(materialId)).thenReturn(Optional.of(material))
        `when`(danceFigureRepository.findById(danceFigureId)).thenReturn(Optional.of(danceFigure))

        val request = FigureRequest(
            danceFigureId = danceFigureId,
            startTime = 10,
            endTime = 15
        )

        val result = materialService.addFigure(materialId, request)

        assertNotNull(result)
        assertEquals(danceFigure, result.danceFigure)
        assertEquals("Natural Turn", result.name)
        assertEquals(10, result.startTime)
        assertEquals(15, result.endTime)
        assertEquals(material, result.material)

        verify(materialRepository).save(material)
        verify(eventPublisher).publishEvent(any(MaterialFigureAddedEvent::class.java))
    }

    @Test
    fun `should update figure timing and trigger update event`() {
        val materialId = UUID.randomUUID()
        val figureId = UUID.randomUUID()
        val danceFigureId = UUID.randomUUID()

        val danceFigure = DanceFigure().apply {
            id = danceFigureId
            name = "Natural Turn"
        }

        val figure = Figure().apply {
            id = figureId
            startTime = 5
            endTime = 10
            this.danceFigure = danceFigure
        }

        val material = Material().apply {
            id = materialId
            name = "Waltz Sequence"
            figures.add(figure)
        }

        figure.material = material

        `when`(materialRepository.findById(materialId)).thenReturn(Optional.of(material))
        `when`(danceFigureRepository.findById(danceFigureId)).thenReturn(Optional.of(danceFigure))

        val request = FigureRequest(
            danceFigureId = danceFigureId,
            startTime = 8,
            endTime = 12
        )

        val result = materialService.updateFigure(materialId, figureId, request)

        assertNotNull(result)
        assertEquals(8, result.startTime)
        assertEquals(12, result.endTime)
        verify(materialRepository).save(material)
        verify(eventPublisher).publishEvent(any(MaterialFigureUpdatedEvent::class.java))
    }

    @Test
    fun `should remove figure and trigger delete event`() {
        val materialId = UUID.randomUUID()
        val figureId = UUID.randomUUID()
        val danceFigureId = UUID.randomUUID()

        val danceFigure = DanceFigure().apply {
            id = danceFigureId
            name = "Natural Turn"
        }

        val figure = Figure().apply {
            id = figureId
            startTime = 5
            endTime = 10
            this.danceFigure = danceFigure
        }

        val material = Material().apply {
            id = materialId
            name = "Waltz Sequence"
            figures.add(figure)
        }

        figure.material = material

        `when`(materialRepository.findById(materialId)).thenReturn(Optional.of(material))

        materialService.removeFigure(materialId, figureId)

        assertEquals(0, material.figures.size)
        verify(materialRepository).save(material)
        verify(eventPublisher).publishEvent(any(MaterialFigureDeletedEvent::class.java))
    }
}
