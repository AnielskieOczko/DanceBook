package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureCreatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID

class DanceFigureServiceTest {

    private lateinit var danceFigureRepository: DanceFigureRepository
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var appUserService: AppUserService
    private lateinit var danceFigureService: DanceFigureServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        danceFigureRepository = mock(DanceFigureRepository::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        appUserService = mock(AppUserService::class.java)
        
        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        danceFigureService = DanceFigureServiceImpl(
            danceFigureRepository,
            danceTypeService,
            eventPublisher,
            appUserService
        )
    }

    @Test
    fun `should create global dance figure`() {
        val danceTypeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = danceTypeId
            name = "Waltz"
        }
        val request = DanceFigureRequest(
            name = "Back Whisk",
            danceTypeId = danceTypeId,
            danceClass = DanceClass.H
        )

        `when`(danceTypeService.findById(danceTypeId)).thenReturn(danceType)
        `when`(danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceTypeId)).thenReturn(emptyList())

        val savedFigure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = request.name
            this.danceType = danceType
            this.danceClass = request.danceClass
            this.predefined = false
        }
        `when`(danceFigureRepository.save(any(DanceFigure::class.java))).thenReturn(savedFigure)

        val result = danceFigureService.create(request)

        assertNotNull(result)
        assertEquals("Back Whisk", result.name)
        assertEquals(DanceClass.H, result.danceClass)
        assertEquals(false, result.predefined)
        verify(eventPublisher).publishEvent(any(DanceFigureCreatedEvent::class.java))
    }

    @Test
    fun `should prevent duplicate figure names for same dance type`() {
        val danceTypeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = danceTypeId
            name = "Waltz"
        }
        val request = DanceFigureRequest(
            name = "Back Whisk",
            danceTypeId = danceTypeId,
            danceClass = DanceClass.H
        )

        `when`(danceTypeService.findById(danceTypeId)).thenReturn(danceType)
        
        val existingFigure = DanceFigure().apply {
            name = "Back Whisk"
            this.danceType = danceType
        }
        `when`(danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceTypeId)).thenReturn(listOf(existingFigure))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            danceFigureService.create(request)
        }

        assertEquals("A figure with the name 'Back Whisk' already exists for this dance.", exception.message)
    }

    @Test
    fun `should create global dance figure with alternative timing`() {
        val danceTypeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = danceTypeId
            name = "Waltz"
        }
        val request = DanceFigureRequest(
            name = "Back Whisk",
            danceTypeId = danceTypeId,
            danceClass = DanceClass.H,
            alternativeTiming = "123&"
        )

        `when`(danceTypeService.findById(danceTypeId)).thenReturn(danceType)
        `when`(danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceTypeId)).thenReturn(emptyList())

        val savedFigure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = request.name
            this.danceType = danceType
            this.danceClass = request.danceClass
            this.predefined = false
            this.alternativeTiming = request.alternativeTiming
        }
        `when`(danceFigureRepository.save(any(DanceFigure::class.java))).thenReturn(savedFigure)

        val result = danceFigureService.create(request)

        assertNotNull(result)
        assertEquals("Back Whisk", result.name)
        assertEquals(DanceClass.H, result.danceClass)
        assertEquals("123&", result.alternativeTiming)
        assertEquals(false, result.predefined)
        verify(eventPublisher).publishEvent(any(DanceFigureCreatedEvent::class.java))
    }

    @Test
    fun `should update existing dance figure`() {
        val figureId = UUID.randomUUID()
        val danceTypeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = danceTypeId
            name = "Waltz"
        }
        val existingFigure = DanceFigure().apply {
            id = figureId
            name = "Old Name"
            this.danceType = danceType
            this.danceClass = DanceClass.E
            this.alternativeTiming = "123"
        }

        val request = DanceFigureRequest(
            name = "New Name",
            danceTypeId = danceTypeId,
            danceClass = DanceClass.D,
            alternativeTiming = "1&2"
        )

        `when`(danceFigureRepository.findById(figureId)).thenReturn(Optional.of(existingFigure))
        `when`(danceTypeService.findById(danceTypeId)).thenReturn(danceType)
        `when`(danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceTypeId)).thenReturn(listOf(existingFigure))
        `when`(danceFigureRepository.save(any(DanceFigure::class.java))).thenAnswer { it.arguments[0] as DanceFigure }

        val result = danceFigureService.update(figureId, request)

        assertNotNull(result)
        assertEquals("New Name", result.name)
        assertEquals(DanceClass.D, result.danceClass)
        assertEquals("1&2", result.alternativeTiming)
        verify(eventPublisher).publishEvent(any(DanceFigureUpdatedEvent::class.java))
    }

    @Test
    fun `should prevent duplicate figure names on update`() {
        val figureId = UUID.randomUUID()
        val otherFigureId = UUID.randomUUID()
        val danceTypeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = danceTypeId
            name = "Waltz"
        }
        val existingFigure = DanceFigure().apply {
            id = figureId
            name = "Old Name"
            this.danceType = danceType
        }
        val otherFigure = DanceFigure().apply {
            id = otherFigureId
            name = "Duplicate Name"
            this.danceType = danceType
        }

        val request = DanceFigureRequest(
            name = "Duplicate Name",
            danceTypeId = danceTypeId
        )

        `when`(danceFigureRepository.findById(figureId)).thenReturn(Optional.of(existingFigure))
        `when`(danceTypeService.findById(danceTypeId)).thenReturn(danceType)
        `when`(danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceTypeId)).thenReturn(listOf(existingFigure, otherFigure))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            danceFigureService.update(figureId, request)
        }

        assertEquals("A figure with the name 'Duplicate Name' already exists for this dance.", exception.message)
    }

    @Test
    fun `should prevent deleting predefined standard figures`() {
        val figureId = UUID.randomUUID()
        val predefinedFigure = DanceFigure().apply {
            id = figureId
            name = "Natural Turn"
            predefined = true
        }

        `when`(danceFigureRepository.findById(figureId)).thenReturn(Optional.of(predefinedFigure))

        val exception = assertThrows(IllegalStateException::class.java) {
            danceFigureService.delete(figureId)
        }

        assertEquals("Cannot delete predefined standard figures.", exception.message)
    }

    @Test
    fun `should allow deleting custom figures`() {
        val figureId = UUID.randomUUID()
        val customFigure = DanceFigure().apply {
            id = figureId
            name = "My Custom Figure"
            predefined = false
            danceType = DanceType().apply { name = "Waltz" }
        }

        `when`(danceFigureRepository.findById(figureId)).thenReturn(Optional.of(customFigure))

        danceFigureService.delete(figureId)

        verify(danceFigureRepository).delete(customFigure)
        verify(eventPublisher).publishEvent(any(DanceFigureDeletedEvent::class.java))
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)
}
