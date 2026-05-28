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
        assertEquals(listOf("Natural Basic Movement"), existingFigure.precedingFigureNames)
        assertEquals(listOf("Whisk To Left"), existingFigure.followingFigureNames)

        verify(danceFigureRepository).save(existingFigure)
        verify(danceFigureStepRepository, times(4)).save(any(com.jankowski.rafal.dancebook.model.DanceFigureStep::class.java))
    }

    @Test
    fun `should parse AI structured JSON content and map steps and comments and notes`() {
        val tempFile = File.createTempFile("ai_parsed_figures_test", ".json")
        tempFile.deleteOnExit()

        val sampleDataset = """
            [
              {
                "name": "Corta Jaca",
                "urls": [
                  "https://www.dancecentral.info/ballroom/international-style/samba/corta-jaca",
                  "https://www.youtube.com/watch?v=example"
                ],
                "dance_type": "SAMBA",
                "level": "Bronze",
                "starting_foot_leader": "RF",
                "ending_foot_leader": "LF",
                "starting_foot_follower": "LF",
                "ending_foot_follower": "RF",
                "starting_position": "Closed Position, facing Wall",
                "ending_position": "Closed Position",
                "preceding_figure_names": "Natural Basic Movement",
                "following_figure_names": "Whisk To Left",
                "notes": "Leader turns 1/4 to L over 7-10\nSome other alternatives.",
                "steps": [
                  {
                    "step_number": 1,
                    "timing": "S",
                    "role": "LEADER",
                    "foot": "RF",
                    "action": "RF fwd, strong step",
                    "footwork": "HF",
                    "alignment": "No turn",
                    "amount_of_turn": null,
                    "comments": [
                      "Man's first step needs to be side, not forward.",
                      "Communicate with lady."
                    ]
                  },
                  {
                    "step_number": 1,
                    "timing": "S",
                    "role": "FOLLOWER",
                    "foot": "LF",
                    "action": "LF back",
                    "footwork": "BF",
                    "alignment": "No turn",
                    "amount_of_turn": null,
                    "comments": [
                      "Timing is hold on 1, step on 2."
                    ]
                  }
                ]
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

        val result = syllabusImporterService.importFromAiParsedJson(tempFile.absolutePath)
        assertEquals(1, result.figuresUpdated)
        assertEquals(2, result.stepsCreated)
        assertEquals(0, result.skippedUnmatched)
        assertEquals(0, result.warnings.size)

        // Verify figure metadata was updated
        assertEquals("RF", existingFigure.startingFootLeader)
        assertEquals("LF", existingFigure.endingFootLeader)
        assertEquals("LF", existingFigure.startingFootFollower)
        assertEquals("RF", existingFigure.endingFootFollower)
        assertEquals("Closed Position, facing Wall", existingFigure.startingPosition)
        assertEquals("Closed Position", existingFigure.endingPosition)
        assertEquals(listOf("Natural Basic Movement"), existingFigure.precedingFigureNames)
        assertEquals(listOf("Whisk To Left"), existingFigure.followingFigureNames)
        assertEquals("Leader turns 1/4 to L over 7-10\nSome other alternatives.", existingFigure.notes)

        // Verify links were created
        assertEquals(2, existingFigure.links.size)
        assertEquals("https://www.dancecentral.info/ballroom/international-style/samba/corta-jaca", existingFigure.links[0].url)
        assertEquals("https://www.youtube.com/watch?v=example", existingFigure.links[1].url)

        verify(danceFigureRepository).save(existingFigure)
        
        // Retrieve saved step argument and verify comments were populated
        val stepCaptor = org.mockito.ArgumentCaptor.forClass(com.jankowski.rafal.dancebook.model.DanceFigureStep::class.java)
        verify(danceFigureStepRepository, times(2)).save(stepCaptor.capture())
        
        val capturedSteps = stepCaptor.allValues
        val leaderStep = capturedSteps.find { it.role == "LEADER" }
        assertNotNull(leaderStep)
        assertEquals(2, leaderStep!!.comments.size)
        assertEquals("Man's first step needs to be side, not forward.", leaderStep.comments[0].commentText)
        assertEquals(1, leaderStep.comments[0].displayOrder)
        assertEquals("Communicate with lady.", leaderStep.comments[1].commentText)
        assertEquals(2, leaderStep.comments[1].displayOrder)

        val followerStep = capturedSteps.find { it.role == "FOLLOWER" }
        assertNotNull(followerStep)
        assertEquals(1, followerStep!!.comments.size)
        assertEquals("Timing is hold on 1, step on 2.", followerStep.comments[0].commentText)
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)
}

