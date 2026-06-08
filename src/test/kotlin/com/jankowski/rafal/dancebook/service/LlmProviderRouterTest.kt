package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class LlmProviderRouterTest {

    private lateinit var openRouterProvider: LlmProvider
    private lateinit var googleAiProvider: LlmProvider
    private lateinit var ollamaProvider: LlmProvider
    private lateinit var router: LlmProviderRouter

    @BeforeEach
    fun setUp() {
        openRouterProvider = mock(LlmProvider::class.java)
        googleAiProvider = mock(LlmProvider::class.java)
        ollamaProvider = mock(LlmProvider::class.java)

        `when`(openRouterProvider.providerName).thenReturn("openrouter")
        `when`(googleAiProvider.providerName).thenReturn("google-ai")
        `when`(ollamaProvider.providerName).thenReturn("ollama")

        `when`(openRouterProvider.getModels()).thenReturn(listOf("or-model"))
        `when`(googleAiProvider.getModels()).thenReturn(listOf("google-model"))
        `when`(ollamaProvider.getModels()).thenReturn(listOf("ollama-model"))

        router = LlmProviderRouter(listOf(openRouterProvider, googleAiProvider, ollamaProvider))
    }

    @Test
    fun `should register and return provider case-insensitively`() {
        val provider = router.getProvider("Google-Ai")
        assertEquals(googleAiProvider, provider)
    }

    @Test
    fun `should throw exception when provider is unknown`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            router.getProvider("unknown-provider")
        }
        assertTrue(exception.message!!.contains("Unknown LLM provider"))
    }

    @Test
    fun `should aggregate models from all providers`() {
        val allModels = router.getAllModels()
        assertEquals(3, allModels.size)
        assertEquals(listOf("or-model"), allModels["openrouter"])
        assertEquals(listOf("google-model"), allModels["google-ai"])
        assertEquals(listOf("ollama-model"), allModels["ollama"])
    }

    @Test
    fun `should delegate callLlm to the correct provider`() {
        val request = LlmRequest("sys", "user", "google-model")
        val expectedResponse = LlmResponse("content", 1, 2, 3, null)

        `when`(googleAiProvider.callLlm(request)).thenReturn(expectedResponse)

        val response = router.callLlm("google-ai", request)

        assertEquals(expectedResponse, response)
        verify(googleAiProvider).callLlm(request)
    }
}
