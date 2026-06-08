package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.GuidedParseJsonRequest
import com.jankowski.rafal.dancebook.dto.GuidedParseResult
import com.jankowski.rafal.dancebook.dto.GuidedParseUrlRequest
import com.jankowski.rafal.dancebook.service.GuidedFigureParseService
import com.jankowski.rafal.dancebook.service.OpenRouterService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.UUID

class GuidedFigureParseControllerTest {

    private lateinit var guidedFigureParseService: GuidedFigureParseService
    private lateinit var openRouterService: OpenRouterService
    private lateinit var controller: GuidedFigureParseController

    @BeforeEach
    fun setUp() {
        guidedFigureParseService = mock(GuidedFigureParseService::class.java)
        openRouterService = mock(OpenRouterService::class.java)
        controller = GuidedFigureParseController(guidedFigureParseService, openRouterService)
    }

    @Test
    fun `should return success parsed result when JSON is valid`() {
        val rawJson = "{}"
        val expectedResult = GuidedParseResult(success = true, request = DanceFigureRequest(name = "Test"))
        `when`(guidedFigureParseService.parseFromJson(rawJson)).thenReturn(expectedResult)

        val response = controller.parseFromJson(GuidedParseJsonRequest(rawJson))

        assertEquals(200, response.statusCode.value())
        val body = response.body
        assertTrue(body?.success == true)
        assertEquals("Test", body?.request?.name)
    }

    @Test
    fun `should parse URL and return result`() {
        val url = "https://example.com"
        val model = "nvidia/nemotron-3-nano-30b-a3b:free"
        val danceTypeId = UUID.randomUUID()
        val expectedResult = GuidedParseResult(success = true, request = DanceFigureRequest(name = "Test from URL"))
        `when`(guidedFigureParseService.parseFromUrl(url, model, danceTypeId)).thenReturn(expectedResult)

        val response = controller.parseFromUrl(GuidedParseUrlRequest(url, model, danceTypeId))

        assertEquals(200, response.statusCode.value())
        val body = response.body
        assertTrue(body?.success == true)
        assertEquals("Test from URL", body?.request?.name)
    }

    @Test
    fun `should get allowed free models`() {
        val models = listOf("model-1", "model-2")
        `when`(openRouterService.getModels()).thenReturn(models)

        val response = controller.getModels()

        assertEquals(200, response.statusCode.value())
        assertEquals(models, response.body)
    }

    @Test
    fun `should get expected json schema`() {
        val response = controller.getExpectedJsonSchema()

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body?.contains("name") == true)
        assertTrue(response.body?.contains("dance_type") == true)
    }
}
