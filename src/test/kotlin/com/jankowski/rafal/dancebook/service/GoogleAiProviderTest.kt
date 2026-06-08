package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
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

class GoogleAiProviderTest {

    private lateinit var properties: GoogleAiProperties
    private lateinit var httpClient: HttpClient
    private lateinit var provider: GoogleAiProvider
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        properties = GoogleAiProperties(
            apiKey = "fake-google-key",
            baseUrl = "https://generativelanguage.googleapis.com",
            allowedModels = listOf("gemini-2.5-flash", "gemma-4-31b")
        )
        httpClient = mock(HttpClient::class.java)
        provider = GoogleAiProvider(properties, objectMapper, httpClient)
    }

    @Test
    fun `should fail if API key is blank`() {
        properties = GoogleAiProperties(apiKey = "")
        provider = GoogleAiProvider(properties, objectMapper, httpClient)

        assertThrows(IllegalStateException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "gemini-2.5-flash"))
        }
    }

    @Test
    fun `should fail if requested model is not in allowed list`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "unallowed-gemini-model"))
        }
    }

    @Test
    fun `should call Google AI and parse candidate content text and usage metadata`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "```json\n{\n  \"name\": \"Gemini Figure\"\n}\n```"
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 150,
                "candidatesTokenCount": 80,
                "totalTokenCount": 230,
                "thoughtsTokenCount": 40
              }
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        val result = provider.callLlm(
            LlmRequest(
                systemPrompt = "sys",
                userPrompt = "user",
                model = "gemini-2.5-flash",
                extras = mapOf("thinkingBudget" to 1024)
            )
        )

        assertEquals("{\n  \"name\": \"Gemini Figure\"\n}", result.content)
        assertEquals(150, result.promptTokens)
        assertEquals(80, result.completionTokens)
        assertEquals(230, result.totalTokens)
        assertEquals(40, result.reasoningTokens)
    }

    @Test
    fun `should throw exception if API returns non-200 status`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(400)
        `when`(mockResponse.body()).thenReturn("Bad Request")

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        assertThrows(RuntimeException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "gemini-2.5-flash"))
        }
    }

    @Test
    fun `should throw exception when response is truncated`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "incomplete json"
                      }
                    ]
                  },
                  "finishReason": "MAX_TOKENS"
                }
              ]
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        val exception = assertThrows(RuntimeException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "gemini-2.5-flash"))
        }
        assertTrue(exception.message!!.contains("truncated"))
    }

    @Test
    fun `should map thinkingBudget to thinkingLevel for Gemini 3 models`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "{}"}]
                  }
                }
              ]
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        // Mock gemini-3.5-flash as allowed model for test
        properties = GoogleAiProperties(
            apiKey = "fake-google-key",
            baseUrl = "https://generativelanguage.googleapis.com",
            allowedModels = listOf("gemini-3.5-flash")
        )
        provider = GoogleAiProvider(properties, objectMapper, httpClient)

        val result = provider.callLlm(
            LlmRequest(
                systemPrompt = "sys",
                userPrompt = "user",
                model = "gemini-3.5-flash",
                extras = mapOf("thinkingBudget" to 2048) // maps to MEDIUM
            )
        )

        assertEquals("{}", result.content)
    }

    @Test
    fun `should not pass thinkingConfig for Gemma models`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "{}"}]
                  }
                }
              ]
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        // Mock gemma-4-31b-it as allowed model for test
        properties = GoogleAiProperties(
            apiKey = "fake-google-key",
            baseUrl = "https://generativelanguage.googleapis.com",
            allowedModels = listOf("gemma-4-31b-it")
        )
        provider = GoogleAiProvider(properties, objectMapper, httpClient)

        val result = provider.callLlm(
            LlmRequest(
                systemPrompt = "sys",
                userPrompt = "user",
                model = "gemma-4-31b-it",
                extras = mapOf("thinkingBudget" to 2048) // Should be ignored
            )
        )

        assertEquals("{}", result.content)
    }
}
