package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.config.OpenRouterProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class NoteRewriteServiceTest {

    private lateinit var llmProviderRouter: LlmProviderRouter
    private lateinit var richTextService: RichTextService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var openRouterProperties: OpenRouterProperties

    @BeforeEach
    fun setUp() {
        llmProviderRouter = mock(LlmProviderRouter::class.java)
        richTextService = mock(RichTextService::class.java)
        objectMapper = ObjectMapper().registerKotlinModule()
        openRouterProperties = OpenRouterProperties(
            apiKey = "test-key",
            defaultModel = "nvidia/nemotron-3-nano-30b-a3b:free"
        )
    }

    private fun service(apiKey: String = "test-key") = NoteRewriteService(
        llmProviderRouter,
        richTextService,
        openRouterProperties.copy(apiKey = apiKey),
        objectMapper
    )

    // ── isAvailable ───────────────────────────────────────────────────────────

    @Test
    fun `isAvailable returns true when api key is present`() {
        assertTrue(service("sk-abc").isAvailable())
    }

    @Test
    fun `isAvailable returns false when api key is blank`() {
        assertFalse(service("").isAvailable())
    }

    @Test
    fun `isAvailable returns false when api key is only whitespace`() {
        assertFalse(service("   ").isAvailable())
    }

    // ── rewrite: sanitise and prompt formatting ───────────────────────────────

    @Test
    fun `rewrite sends cleaned HTML including links and formatting to LLM`() {
        val rawDescription = "<p>Class notes with <a href=\"https://example.com/step\">video</a></p>"
        val cleanInput = "<p>Class notes with <a href=\"https://example.com/step\">video</a></p>"
        val llmJson = """{"rewrite":"<p>Cleaned note with <a href=\"https://example.com/step\">video</a></p>"}"""
        val sanitisedProposal = "<p>Cleaned note with <a href=\"https://example.com/step\">video</a></p>"

        `when`(richTextService.clean(rawDescription)).thenReturn(cleanInput)
        val captor = ArgumentCaptor.forClass(LlmRequest::class.java)
        `when`(llmProviderRouter.callLlm(anyString(), capture(captor, LlmRequest("", "", "")))).thenReturn(
            LlmResponse(
                content = llmJson,
                promptTokens = 50,
                completionTokens = 30,
                totalTokens = 80,
                reasoningTokens = null
            )
        )
        `when`(richTextService.clean("<p>Cleaned note with <a href=\"https://example.com/step\">video</a></p>"))
            .thenReturn(sanitisedProposal)

        val result = service().rewrite(rawDescription)

        assertEquals(sanitisedProposal, result)
        assertEquals(cleanInput, captor.value.userPrompt)
        assertTrue(captor.value.systemPrompt.contains("<a href=\"...\">"))
        verify(richTextService).clean(rawDescription)
        verify(richTextService).clean("<p>Cleaned note with <a href=\"https://example.com/step\">video</a></p>")
    }

    @Test
    fun `rewrite extracts the rewrite field from the JSON envelope`() {
        val rawDescription = "some note text"
        `when`(richTextService.clean(rawDescription)).thenReturn(rawDescription)

        val llmJson = """{"model":"test","rewrite":"<p>Cleaned up text.</p>","tokens":42}"""
        `when`(llmProviderRouter.callLlm(anyString(), anyKotlin(LlmRequest("", "", "")))).thenReturn(
            LlmResponse(
                content = llmJson,
                promptTokens = 10,
                completionTokens = 10,
                totalTokens = 20,
                reasoningTokens = null
            )
        )
        `when`(richTextService.clean("<p>Cleaned up text.</p>")).thenReturn("<p>Cleaned up text.</p>")

        val result = service().rewrite(rawDescription)

        assertTrue(result.contains("Cleaned up text"))
    }

    // ── rewrite: JSON extraction error handling ───────────────────────────────

    @Test
    fun `rewrite throws when LLM returns JSON with empty rewrite field`() {
        val rawDescription = "<p>Some text</p>"
        `when`(richTextService.clean(rawDescription)).thenReturn(rawDescription)
        `when`(llmProviderRouter.callLlm(anyString(), anyKotlin(LlmRequest("", "", "")))).thenReturn(
            LlmResponse(
                content = """{"rewrite":""}""",
                promptTokens = 10,
                completionTokens = 5,
                totalTokens = 15,
                reasoningTokens = null
            )
        )

        val ex = assertThrows<IllegalStateException> { service().rewrite(rawDescription) }
        assertTrue(ex.message!!.contains("empty or missing rewrite field"))
        verify(richTextService, never()).clean("")
    }

    @Test
    fun `rewrite throws when LLM returns JSON with missing rewrite field`() {
        val rawDescription = "<p>Some text</p>"
        `when`(richTextService.clean(rawDescription)).thenReturn(rawDescription)
        `when`(llmProviderRouter.callLlm(anyString(), anyKotlin(LlmRequest("", "", "")))).thenReturn(
            LlmResponse(
                content = """{"other_field":"test"}""",
                promptTokens = 10,
                completionTokens = 5,
                totalTokens = 15,
                reasoningTokens = null
            )
        )

        val ex = assertThrows<IllegalStateException> { service().rewrite(rawDescription) }
        assertTrue(ex.message!!.contains("empty or missing rewrite field"))
    }

    @Test
    fun `rewrite falls back to raw content when response is not JSON`() {
        val rawDescription = "<p>Some text</p>"
        val rawHtmlResponse = "<p>Cleaned up text without JSON envelope</p>"
        `when`(richTextService.clean(rawDescription)).thenReturn(rawDescription)
        `when`(llmProviderRouter.callLlm(anyString(), anyKotlin(LlmRequest("", "", "")))).thenReturn(
            LlmResponse(
                content = rawHtmlResponse,
                promptTokens = 10,
                completionTokens = 10,
                totalTokens = 20,
                reasoningTokens = null
            )
        )
        `when`(richTextService.clean(rawHtmlResponse)).thenReturn(rawHtmlResponse)

        val result = service().rewrite(rawDescription)

        assertEquals(rawHtmlResponse, result)
        verify(richTextService).clean(rawHtmlResponse)
    }

    // ── rewrite: provider failure ─────────────────────────────────────────────

    @Test
    fun `rewrite propagates exception when provider fails`() {
        val rawDescription = "some text"
        `when`(richTextService.clean(rawDescription)).thenReturn(rawDescription)
        `when`(llmProviderRouter.callLlm(anyString(), anyKotlin(LlmRequest("", "", ""))))
            .thenThrow(RuntimeException("OpenRouter API returned error status 500: Internal Server Error"))

        val ex = assertThrows<RuntimeException> { service().rewrite(rawDescription) }
        assertTrue(ex.message!!.contains("500") || ex.message!!.contains("error"))
    }

    @Test
    fun `rewrite throws when sanitised result is empty`() {
        val rawDescription = "some text"
        `when`(richTextService.clean(rawDescription)).thenReturn(rawDescription)
        `when`(llmProviderRouter.callLlm(anyString(), anyKotlin(LlmRequest("", "", "")))).thenReturn(
            LlmResponse(
                content = """{"rewrite":"<br>"}""",
                promptTokens = 10,
                completionTokens = 5,
                totalTokens = 15,
                reasoningTokens = null
            )
        )
        `when`(richTextService.clean("<br>")).thenReturn(null)

        assertThrows<IllegalStateException> { service().rewrite(rawDescription) }
    }

    // ── rewrite: blank input rejection ────────────────────────────────────────

    @Test
    fun `rewrite does not call llm when text is blank`() {
        val rawDescription = "   "
        `when`(richTextService.clean(rawDescription)).thenReturn(null)

        val ex = assertThrows<IllegalArgumentException> { service().rewrite(rawDescription) }
        assertTrue(ex.message!!.contains("must not be blank"))
        verify(llmProviderRouter, never()).callLlm(anyKotlin(""), anyKotlin(LlmRequest("", "", "")))
    }

    @Test
    fun `rewrite does not call llm when html has no text content`() {
        val emptyHtml = "<div><br></div>"
        `when`(richTextService.clean(emptyHtml)).thenReturn(null)

        val ex = assertThrows<IllegalArgumentException> { service().rewrite(emptyHtml) }
        assertTrue(ex.message!!.contains("must not be blank"))
        verify(llmProviderRouter, never()).callLlm(anyKotlin(""), anyKotlin(LlmRequest("", "", "")))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun anyString(): String {
        any<String>()
        return ""
    }

    private fun <T> anyKotlin(value: T): T {
        any<T>()
        return value
    }

    private fun <T> capture(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }
}
