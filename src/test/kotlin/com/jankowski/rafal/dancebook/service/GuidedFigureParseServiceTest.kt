package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.dto.GuidedParseResult
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID

class GuidedFigureParseServiceTest {

    private lateinit var openRouterService: OpenRouterService
    private lateinit var danceFigureRepository: DanceFigureRepository
    private lateinit var danceTypeRepository: DanceTypeRepository
    private lateinit var guidedFigureParseService: GuidedFigureParseService
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        openRouterService = mock(OpenRouterService::class.java)
        danceFigureRepository = mock(DanceFigureRepository::class.java)
        danceTypeRepository = mock(DanceTypeRepository::class.java)

        guidedFigureParseService = spy(
            GuidedFigureParseService(
                openRouterService,
                danceFigureRepository,
                danceTypeRepository,
                objectMapper
            )
        )
    }

    @Test
    fun `should parse valid pasted JSON correctly`() {
        val json = """
            {
              "name": "Natural Spin Turn",
              "dance_type": "WALTZ",
              "level": "Bronze",
              "starting_foot_leader": "RF",
              "ending_foot_leader": "LF",
              "steps": [
                {
                  "step_number": 1,
                  "timing": "S",
                  "role": "LEADER",
                  "foot": "RF",
                  "action": "Step forward turning R"
                }
              ]
            }
        """.trimIndent()

        val waltzType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        `when`(danceTypeRepository.findAll()).thenReturn(listOf(waltzType))

        val result = guidedFigureParseService.parseFromJson(json)

        assertTrue(result.success)
        assertNotNull(result.request)
        val request = result.request!!
        assertEquals("Natural Spin Turn", request.name)
        assertEquals(waltzType.id, request.danceTypeId)
        assertEquals(DanceClass.H, request.danceClass) // Bronze maps to H
        assertEquals("RF", request.startingFootLeader)
        assertEquals("LF", request.endingFootLeader)
        assertEquals(1, request.steps.size)
        assertEquals("S", request.steps[0].timing)
        assertEquals("LEADER", request.steps[0].role)
        assertEquals("RF", request.steps[0].foot)
        assertEquals("Step forward turning R", request.steps[0].action)
    }

    @Test
    fun `should return failure for invalid JSON structure`() {
        val result = guidedFigureParseService.parseFromJson("invalid json")
        assertFalse(result.success)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors[0].contains("Invalid JSON structure"))
    }

    @Test
    fun `should parse URL page text using LLM correctly`() {
        val url = "https://www.dancecentral.info/ballroom/international-style/samba/corta-jaca"
        val model = "nvidia/nemotron-3-nano-30b-a3b:free"
        
        doReturn("<html><body>Some raw crawled page body text</body></html>")
            .`when`(guidedFigureParseService).fetchUrlContent(url)

        val waltzType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        `when`(danceTypeRepository.findAll()).thenReturn(listOf(waltzType))
        `when`(danceFigureRepository.findAll()).thenReturn(emptyList())

        val mockLlmResponse = """
            {
              "name": "Natural Spin Turn",
              "dance_type": "WALTZ",
              "level": "Bronze",
              "starting_foot_leader": "RF",
              "ending_foot_leader": "LF",
              "steps": [
                {
                  "step_number": 1,
                  "timing": "S",
                  "role": "LEADER",
                  "foot": "RF",
                  "action": "Step forward"
                }
              ]
            }
        """.trimIndent()

        `when`(openRouterService.callLlm(anyString(), anyString(), eq(model))).thenReturn(mockLlmResponse)

        val result = guidedFigureParseService.parseFromUrl(url, model, waltzType.id)

        assertTrue(result.success)
        assertNotNull(result.request)
        val request = result.request!!
        assertEquals("Natural Spin Turn", request.name)
        assertEquals(waltzType.id, request.danceTypeId)
        assertEquals(1, request.steps.size)
        
        // Check source URL is appended to links
        assertEquals(1, request.links.size)
        assertEquals(url, request.links[0].url)
        assertEquals("syllabus", request.links[0].type)
    }
}
