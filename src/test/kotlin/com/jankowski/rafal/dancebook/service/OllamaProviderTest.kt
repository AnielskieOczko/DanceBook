package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.config.OllamaProperties
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

class OllamaProviderTest {

    private lateinit var properties: OllamaProperties
    private lateinit var httpClient: HttpClient
    private lateinit var provider: OllamaProvider
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        properties = OllamaProperties(
            apiKey = "fake-ollama-key",
            baseUrl = "https://ollama.com",
            allowedModels = listOf("gemma4", "llama3.2")
        )
        httpClient = mock(HttpClient::class.java)
        provider = OllamaProvider(properties, objectMapper, httpClient)
    }

    @Test
    fun `should fail if API key is blank`() {
        properties = OllamaProperties(apiKey = "", allowedModels = listOf("gemma4"))
        provider = OllamaProvider(properties, objectMapper, httpClient)

        assertThrows(IllegalStateException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "gemma4"))
        }
    }

    @Test
    fun `should fail if model is not in allowed list`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "unallowed-ollama-model"))
        }
    }

    @Test
    fun `should call Ollama Cloud and parse response correctly`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "model": "gemma4",
              "done_reason": "stop",
              "message": {
                "role": "assistant",
                "content": "```json\n{\n  \"name\": \"Ollama Figure\"\n}\n```"
              },
              "done": true,
              "prompt_eval_count": 120,
              "eval_count": 60
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        val result = provider.callLlm(LlmRequest("sys", "user", "gemma4"))

        assertEquals("{\n  \"name\": \"Ollama Figure\"\n}", result.content)
        assertEquals(120, result.promptTokens)
        assertEquals(60, result.completionTokens)
        assertEquals(180, result.totalTokens)
    }

    @Test
    fun `should throw exception if API returns error status`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(500)
        `when`(mockResponse.body()).thenReturn("Internal Server Error")

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        assertThrows(RuntimeException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "gemma4"))
        }
    }

    @Test
    fun `should throw exception when response is truncated`() {
        val mockResponse = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(mockResponse.statusCode()).thenReturn(200)
        `when`(mockResponse.body()).thenReturn("""
            {
              "model": "gemma4",
              "done_reason": "length",
              "message": {
                "role": "assistant",
                "content": "incomplete"
              },
              "done": true
            }
        """.trimIndent())

        `when`(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(mockResponse)

        val exception = assertThrows(RuntimeException::class.java) {
            provider.callLlm(LlmRequest("sys", "user", "gemma4"))
        }
        assertTrue(exception.message!!.contains("truncated"))
    }
}
