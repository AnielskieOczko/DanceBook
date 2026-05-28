package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureStepRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.io.File
import java.util.UUID

class SyllabusImporterServiceTest {

    private lateinit var danceFigureRepository: DanceFigureRepository
    private lateinit var danceFigureStepRepository: DanceFigureStepRepository
    private lateinit var danceTypeRepository: DanceTypeRepository
    private lateinit var syllabusImporterService: SyllabusImporterService
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        danceFigureRepository = mock(DanceFigureRepository::class.java)
        danceFigureStepRepository = mock(DanceFigureStepRepository::class.java)
        danceTypeRepository = mock(DanceTypeRepository::class.java)
        
        syllabusImporterService = SyllabusImporterService(
            danceFigureRepository,
            danceFigureStepRepository,
            danceTypeRepository,
            objectMapper
        )
    }

    @Test
    fun `should normalize figure names correctly`() {
        assertEquals("cortajaca", syllabusImporterService.normalizeName("Corta Jaca", "Samba"))
        assertEquals("cortajaca", syllabusImporterService.normalizeName("Samba Corta Jaca", "Samba"))
        assertEquals("closedchange", syllabusImporterService.normalizeName("Closed Changes", "Waltz"))
        assertEquals("closedchange", syllabusImporterService.normalizeName("Closed Change", "Waltz"))
        assertEquals("naturalspinturn", syllabusImporterService.normalizeName("Natural Spin Turn", "Waltz"))
        // Test diacritics
        assertEquals("reversecorte", syllabusImporterService.normalizeName("Reverse Corté", "Waltz"))
        assertEquals("chasse", syllabusImporterService.normalizeName("Chassé", "Waltz"))
    }

    @Test
    fun `should parse dynamic content and map steps`() {
        val tempFile = File.createTempFile("scraped_figures_test", ".json")
        tempFile.deleteOnExit()

        val sampleDataset = """
            [
              {
                "url": "https://www.dancecentral.info/ballroom/international-style/samba/corta-jaca",
                "text": "Dance Central - Corta Jaca\nSamba Corta Jaca\nThe Samba Corta Jaca is Bronze Level figure.\nLeader\nStart in Closed Position, facing Wall. No bounce.\nS: RF fwd, strong step | No turn | HF\nQ: LF fwd and slightly to side | -- | H\nFollower\nS: LF back | No turn | BF\nQ: RF back and slightly to side | -- | B\nPreceding Figures\nNatural Basic Movement\nFollowing Figures\nWhisk To Left"
              }
            ]
        """.trimIndent()

        tempFile.writeText(sampleDataset)

        val sambaId = UUID.randomUUID()
        val sambaType = DanceType().apply {
            id = sambaId
            name = "Samba"
        }
        `when`(danceTypeRepository.findAll()).thenReturn(listOf(sambaType))

        val existingFigure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Corta Jaca"
            danceType = sambaType
        }
        `when`(danceFigureRepository.findByDanceTypeIdOrderByNameAsc(sambaId)).thenReturn(listOf(existingFigure))

        val result = syllabusImporterService.importFromDataset(tempFile.absolutePath)
        assertEquals(1, result.figuresUpdated)
        assertEquals(4, result.stepsCreated)
        assertEquals(0, result.skippedUnmatched)
        println("WARNINGS FOUND: " + result.warnings)
        assertEquals(0, result.warnings.size)

        // Verify figure metadata was updated
        assertEquals("RF", existingFigure.startingFootLeader)
        assertEquals("LF", existingFigure.endingFootLeader) // last step is LF
        assertEquals("LF", existingFigure.startingFootFollower)
        assertEquals("RF", existingFigure.endingFootFollower)

        assertEquals("Closed Position, facing Wall", existingFigure.startingPosition)
        assertEquals("Natural Basic Movement", existingFigure.precedingFigureNames)
        assertEquals("Whisk To Left", existingFigure.followingFigureNames)

        verify(danceFigureRepository).save(existingFigure)
        verify(danceFigureStepRepository, times(4)).save(any(com.jankowski.rafal.dancebook.model.DanceFigureStep::class.java))
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)
}
