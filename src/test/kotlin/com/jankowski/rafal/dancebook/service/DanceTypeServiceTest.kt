package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceTypeRequest
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.Optional
import java.util.UUID

class DanceTypeServiceTest {

    private lateinit var danceTypeRepository: DanceTypeRepository
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var danceTypeService: DanceTypeServiceImpl

    @BeforeEach
    fun setUp() {
        danceTypeRepository = mock(DanceTypeRepository::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        danceTypeService = DanceTypeServiceImpl(danceTypeRepository, danceCategoryService)
    }

    @Test
    fun `findAll returns what the repository returns`() {
        val types = listOf(
            DanceType().apply { name = "Waltz" },
            DanceType().apply { name = "Tango" }
        )
        `when`(danceTypeRepository.findAll()).thenReturn(types)

        val result = danceTypeService.findAll()

        assertEquals(types, result)
        verify(danceTypeRepository).findAll()
    }

    @Test
    fun `findById returns the entity when present`() {
        val id = UUID.randomUUID()
        val type = DanceType().apply {
            this.id = id
            name = "Waltz"
        }
        `when`(danceTypeRepository.findById(id)).thenReturn(Optional.of(type))

        val result = danceTypeService.findById(id)

        assertEquals(type, result)
        verify(danceTypeRepository).findById(id)
    }

    @Test
    fun `findById throws EntityNotFoundException when the repository returns Optional empty`() {
        val id = UUID.randomUUID()
        `when`(danceTypeRepository.findById(id)).thenReturn(Optional.empty())

        val exception = assertThrows(EntityNotFoundException::class.java) {
            danceTypeService.findById(id)
        }

        assertEquals("DanceType with ID $id not found", exception.message)
        verify(danceTypeRepository).findById(id)
    }

    @Test
    fun `findByCategoryId delegates to danceTypeRepository findByCategoryId`() {
        val categoryId = UUID.randomUUID()
        val types = listOf(
            DanceType().apply { name = "Cha Cha" },
            DanceType().apply { name = "Rumba" }
        )
        `when`(danceTypeRepository.findByCategoryId(categoryId)).thenReturn(types)

        val result = danceTypeService.findByCategoryId(categoryId)

        assertEquals(types, result)
        verify(danceTypeRepository).findByCategoryId(categoryId)
    }

    @Test
    fun `create maps name, resolves categoryId via danceCategoryService findById, saves`() {
        val categoryId = UUID.randomUUID()
        val category = DanceCategory().apply {
            this.id = categoryId
            name = "Standard"
        }
        val request = DanceTypeRequest(name = "Slow Foxtrot", categoryId = categoryId)

        `when`(danceCategoryService.findById(categoryId)).thenReturn(category)
        `when`(danceTypeRepository.save(any(DanceType::class.java))).thenAnswer { it.arguments[0] as DanceType }

        val created = danceTypeService.create(request)

        assertEquals("Slow Foxtrot", created.name)
        assertEquals(category, created.category)
        verify(danceCategoryService).findById(categoryId)
        verify(danceTypeRepository).save(any(DanceType::class.java))
    }

    @Test
    fun `create leaves category null when categoryId is null and never calls danceCategoryService`() {
        val request = DanceTypeRequest(name = "Salsa", categoryId = null)
        `when`(danceTypeRepository.save(any(DanceType::class.java))).thenAnswer { it.arguments[0] as DanceType }

        val created = danceTypeService.create(request)

        assertEquals("Salsa", created.name)
        assertNull(created.category)
        verifyNoInteractions(danceCategoryService)
        verify(danceTypeRepository).save(any(DanceType::class.java))
    }

    @Test
    fun `update loads the existing entity, overwrites name and category, saves`() {
        val id = UUID.randomUUID()
        val oldCategory = DanceCategory().apply {
            this.id = UUID.randomUUID()
            name = "Standard"
        }
        val existing = DanceType().apply {
            this.id = id
            name = "Old Name"
            category = oldCategory
        }
        `when`(danceTypeRepository.findById(id)).thenReturn(Optional.of(existing))

        val newCategoryId = UUID.randomUUID()
        val newCategory = DanceCategory().apply {
            this.id = newCategoryId
            name = "Latin"
        }
        `when`(danceCategoryService.findById(newCategoryId)).thenReturn(newCategory)
        `when`(danceTypeRepository.save(existing)).thenReturn(existing)

        val request = DanceTypeRequest(name = "New Name", categoryId = newCategoryId)
        val updated = danceTypeService.update(id, request)

        assertEquals(id, updated.id)
        assertEquals("New Name", updated.name)
        assertEquals(newCategory, updated.category)
        verify(danceTypeRepository).findById(id)
        verify(danceCategoryService).findById(newCategoryId)
        verify(danceTypeRepository).save(existing)
    }

    @Test
    fun `update propagates EntityNotFoundException for an unknown id`() {
        val id = UUID.randomUUID()
        `when`(danceTypeRepository.findById(id)).thenReturn(Optional.empty())

        val request = DanceTypeRequest(name = "New Name", categoryId = null)

        val exception = assertThrows(EntityNotFoundException::class.java) {
            danceTypeService.update(id, request)
        }

        assertEquals("DanceType with ID $id not found", exception.message)
        verify(danceTypeRepository).findById(id)
        verify(danceTypeRepository, never()).save(any(DanceType::class.java))
        verifyNoInteractions(danceCategoryService)
    }

    @Test
    fun `delete loads the entity first, then passes it to danceTypeRepository delete`() {
        val id = UUID.randomUUID()
        val existing = DanceType().apply {
            this.id = id
            name = "Paso Doble"
        }
        `when`(danceTypeRepository.findById(id)).thenReturn(Optional.of(existing))

        danceTypeService.delete(id)

        verify(danceTypeRepository).findById(id)
        verify(danceTypeRepository).delete(existing)
    }
}
