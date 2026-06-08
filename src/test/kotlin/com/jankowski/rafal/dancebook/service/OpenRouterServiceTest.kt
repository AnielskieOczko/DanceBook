package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.config.OpenRouterProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.net.http.HttpClient
import java.net.http.HttpResponse

class OpenRouterServiceTest {

    private lateinit var properties: OpenRouterProperties
    private lateinit var httpClient: HttpClient
    private lateinit var openRouterService: OpenRouterService
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        properties = OpenRouterProperties(
            apiKey = "fake-api-key",
            defaultModel = "nvidia/nemotron-3-nano-30b-a3b:free",
            allowedFreeModels = listOf("nvidia/nemotron-3-nano-30b-a3b:free", "google/gemini-2.5-flash:free")
        )
        httpClient = mock(HttpClient::class.java)
        openRouterService = OpenRouterService(properties, objectMapper, httpClient)
    }

    @Test
    fun `should fail if API key is blank`() {
        properties = OpenRouterProperties(apiKey = "")
        openRouterService = OpenRouterService(properties, objectMapper, httpClient)

        assertThrows(IllegalStateException::class.java) {
            openRouterService.callLlm("sys", "user")
        }
    }

    @Test
    fun `should fail if requested model is not in allowed free list`() {
        assertThrows(IllegalArgumentException::class.java) {
            openRouterService.callLlm("sys", "user", "expensive-model")
        }
    }

    @Test
    fun `should call LLM and extract clean content from JSON response`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "choices": [
                {
                  "finish_reason": "stop",
                  "message": {
                    "role": "assistant",
                    "content": "```json\n{\n  \"name\": \"Test Figure\"\n}\n```"
                  }
                }
              ],
              "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 50,
                "total_tokens": 150,
                "completion_tokens_details": {
                  "reasoning_tokens": 30
                }
              }
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        val result = openRouterService.callLlm("sys", "user")

        assertEquals("{\n  \"name\": \"Test Figure\"\n}", result.content)
        assertEquals(100, result.promptTokens)
        assertEquals(50, result.completionTokens)
        assertEquals(150, result.totalTokens)
        assertEquals(30, result.reasoningTokens)
    }

    @Test
    fun `should throw exception if API returns error status`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(401)
        `when`(mockResponse.body()).thenReturn("Unauthorized")

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        assertThrows(RuntimeException::class.java) {
            openRouterService.callLlm("sys", "user")
        }
    }

    @Test
    fun `should throw exception when LLM response is truncated due to token limit`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "choices": [
                {
                  "finish_reason": "length",
                  "message": {
                    "role": "assistant",
                    "content": "{\"name\": \"Truncated Figure\", \"steps\": ["
                  }
                }
              ]
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        val exception = assertThrows(RuntimeException::class.java) {
            openRouterService.callLlm("sys", "user")
        }
        assertTrue(exception.message!!.contains("truncated"))
    }
}
