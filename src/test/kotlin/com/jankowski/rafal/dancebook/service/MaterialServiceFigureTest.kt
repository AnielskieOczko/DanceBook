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

import com.jankowski.rafal.dancebook.model.Visibility

class MaterialServiceFigureTest {

    private lateinit var materialRepository: MaterialRepository
    private lateinit var figureRepository: FigureRepository
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var danceFigureRepository: DanceFigureRepository
    private lateinit var googleDriveService: GoogleDriveService
    private lateinit var uploadedFileRepository: com.jankowski.rafal.dancebook.repository.UploadedFileRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var appUserService: AppUserService
    private lateinit var commentRepository: com.jankowski.rafal.dancebook.repository.CommentRepository
    private lateinit var materialService: MaterialServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        materialRepository = mock(MaterialRepository::class.java)
        figureRepository = mock(FigureRepository::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        danceFigureRepository = mock(DanceFigureRepository::class.java)
        googleDriveService = mock(GoogleDriveService::class.java)
        uploadedFileRepository = mock(com.jankowski.rafal.dancebook.repository.UploadedFileRepository::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        appUserService = mock(AppUserService::class.java)
        commentRepository = mock(com.jankowski.rafal.dancebook.repository.CommentRepository::class.java)
        
        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)

        materialService = MaterialServiceImpl(
            materialRepository,
            figureRepository,
            danceTypeService,
            danceFigureRepository,
            googleDriveService,
            uploadedFileRepository,
            eventPublisher,
            appUserService,
            RichTextServiceImpl(),
            commentRepository
        )
    }

    @Test
    fun `should link standard figure to material sequence`() {
        val materialId = UUID.randomUUID()
        val danceFigureId = UUID.randomUUID()

        val material = Material().apply {
            id = materialId
            name = "Waltz Sequence"
            owner = currentUser
            visibility = Visibility.PUBLIC
        }

        val danceFigure = DanceFigure().apply {
            id = danceFigureId
            name = "Natural Turn"
        }

        `when`(materialRepository.findOne(any())).thenReturn(Optional.of(material))
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
    fun `should pin figure with default 0-0 timing without time fields`() {
        val materialId = UUID.randomUUID()
        val danceFigureId = UUID.randomUUID()

        val material = Material().apply {
            id = materialId
            name = "Note on Waltz"
            owner = currentUser
            visibility = Visibility.PUBLIC
        }

        val danceFigure = DanceFigure().apply {
            id = danceFigureId
            name = "Reverse Turn"
        }

        `when`(materialRepository.findOne(any())).thenReturn(Optional.of(material))
        `when`(danceFigureRepository.findById(danceFigureId)).thenReturn(Optional.of(danceFigure))

        // Default FigureRequest has startTime=0, endTime=0
        val request = FigureRequest(danceFigureId = danceFigureId)

        val result = materialService.addFigure(materialId, request)

        assertNotNull(result)
        assertEquals(danceFigure, result.danceFigure)
        assertEquals("Reverse Turn", result.name)
        assertEquals(0, result.startTime)
        assertEquals(0, result.endTime)
        assertEquals(1, material.figures.size)
        verify(materialRepository).save(material)
        verify(eventPublisher).publishEvent(any(MaterialFigureAddedEvent::class.java))
    }

    @Test
    fun `pinning duplicate figure has no effect and never adds duplicate`() {
        val materialId = UUID.randomUUID()
        val danceFigureId = UUID.randomUUID()

        val danceFigure = DanceFigure().apply {
            id = danceFigureId
            name = "Natural Turn"
        }

        val existingFigure = Figure().apply {
            id = UUID.randomUUID()
            this.danceFigure = danceFigure
            startTime = 0
            endTime = 0
        }

        val material = Material().apply {
            id = materialId
            name = "Waltz Sequence"
            owner = currentUser
            visibility = Visibility.PUBLIC
            figures.add(existingFigure)
        }
        existingFigure.material = material

        `when`(materialRepository.findOne(any())).thenReturn(Optional.of(material))
        `when`(danceFigureRepository.findById(danceFigureId)).thenReturn(Optional.of(danceFigure))

        val request = FigureRequest(danceFigureId = danceFigureId)

        val result = materialService.addFigure(materialId, request)

        assertEquals(existingFigure, result)
        assertEquals(1, material.figures.size)
        // Verify repository save and event publisher were NOT called for duplicate
        org.mockito.Mockito.verify(materialRepository, org.mockito.Mockito.never()).save(material)
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(MaterialFigureAddedEvent::class.java))
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
            owner = currentUser
            visibility = Visibility.PUBLIC
            figures.add(figure)
        }

        figure.material = material

        `when`(materialRepository.findOne(any())).thenReturn(Optional.of(material))
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
            owner = currentUser
            visibility = Visibility.PUBLIC
            figures.add(figure)
        }

        figure.material = material

        `when`(materialRepository.findOne(any())).thenReturn(Optional.of(material))

        materialService.removeFigure(materialId, figureId)

        assertEquals(0, material.figures.size)
        verify(materialRepository).save(material)
        verify(eventPublisher).publishEvent(any(MaterialFigureDeletedEvent::class.java))
    }

    @Test
    fun `findFigureCounts returns empty map when ids list is empty without querying repository`() {
        val result = materialService.findFigureCounts(emptyList())
        assertEquals(emptyMap<UUID, Int>(), result)
        org.mockito.Mockito.verifyNoInteractions(figureRepository)
    }

    @Test
    fun `findFigureCounts batches figure counts for multiple materials`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val counts = listOf(
            com.jankowski.rafal.dancebook.dto.MaterialFigureCount(id1, 3L),
            com.jankowski.rafal.dancebook.dto.MaterialFigureCount(id2, 1L)
        )
        `when`(figureRepository.countFiguresByMaterialIds(listOf(id1, id2))).thenReturn(counts)

        val result = materialService.findFigureCounts(listOf(id1, id2))

        assertEquals(mapOf(id1 to 3, id2 to 1), result)
        verify(figureRepository).countFiguresByMaterialIds(listOf(id1, id2))
    }
}
