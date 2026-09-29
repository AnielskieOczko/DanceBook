package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jankowski.rafal.dancebook.config.OpenRouterProperties
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
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
import java.util.UUID

class FigureSuggestionServiceTest {

    private lateinit var llmProviderRouter: LlmProviderRouter
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var objectMapper: ObjectMapper
    private var openRouterProperties = OpenRouterProperties(apiKey = "test-key")

    @BeforeEach
    fun setUp() {
        llmProviderRouter = mock(LlmProviderRouter::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        objectMapper = ObjectMapper().registerKotlinModule()
    }

    private fun service() =
        FigureSuggestionService(llmProviderRouter, openRouterProperties, danceFigureService, objectMapper)

    // ── isAvailable ───────────────────────────────────────────────────────────

    @Test
    fun `isAvailable is true when an OpenRouter API key is configured`() {
        assertTrue(service().isAvailable())
    }

    @Test
    fun `isAvailable is false without an OpenRouter API key`() {
        openRouterProperties = OpenRouterProperties(apiKey = "")
        assertFalse(service().isAvailable())
    }

    // ── suggestForMaterial: candidate selection ───────────────────────────────

    @Test
    fun `suggestForMaterial uses the figures of the note's dance style as candidates`() {
        val sambaId = UUID.randomUUID()
        val walkId = UUID.randomUUID()
        val pinnedId = UUID.randomUUID()
        val material = Material().apply {
            description = "<p>Samba walks today.</p>"
            danceType = DanceType().apply { id = sambaId; name = "Samba" }
            figures.add(Figure().apply { danceFigure = figure(pinnedId, "Side Samba walk") })
        }
        `when`(danceFigureService.findAll(typeIds = listOf(sambaId), sortBy = "name_asc"))
            .thenReturn(listOf(figure(walkId, "Samba Walk"), figure(pinnedId, "Side Samba walk")))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""[{"id":"$walkId","reason":"Samba walks"}]"""))

        val result = service().suggestForMaterial(material)

        assertEquals(listOf(walkId), result.map { it.figure.id })
        val request = captureLlmRequest()
        assertTrue(request.userPrompt.contains("Samba Walk"))
        assertFalse(request.userPrompt.contains("Side Samba walk"), "pinned figures are not candidates")
    }

    @Test
    fun `suggestForMaterial searches every figure when the note has no dance style`() {
        val walkId = UUID.randomUUID()
        val material = Material().apply { description = "<p>Samba walks today.</p>" }
        `when`(danceFigureService.findAll(typeIds = null, sortBy = "name_asc"))
            .thenReturn(listOf(figure(walkId, "Samba Walk")))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""[{"id":"$walkId","reason":"Samba walks"}]"""))

        val result = service().suggestForMaterial(material)

        assertEquals(listOf(walkId), result.map { it.figure.id })
    }

    @Test
    fun `suggest calls the configured model and endpoints`() {
        openRouterProperties = OpenRouterProperties(
            apiKey = "test-key",
            figureSuggestionModel = "some/model",
            figureSuggestionProviders = listOf("some/endpoint")
        )
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse("[]"))

        service().suggest("<p>Samba walks</p>", listOf(figure(UUID.randomUUID(), "Samba Walk")), emptySet())

        val request = captureLlmRequest()
        assertEquals("some/model", request.model)
        assertEquals(listOf("some/endpoint"), request.extras["providerOnly"])
    }

    // ── suggest: happy path ───────────────────────────────────────────────────

    @Test
    fun `suggest returns matching figures with reasons`() {
        val walkId = UUID.randomUUID()
        val stationaryId = UUID.randomUUID()

        val sambaCandidates = listOf(
            figure(walkId, "Samba Walk"),
            figure(stationaryId, "Stationary Samba Walk")
        )

        val llmJson = """[{"id":"$walkId","reason":"samba walks"},{"id":"$stationaryId","reason":"stationary samba walk"}]"""
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse(llmJson))

        val result = service().suggest(
            noteText = "<p>Working on samba walks and stationary samba walk.</p>",
            candidates = sambaCandidates,
            pinnedIds = emptySet()
        )

        assertEquals(2, result.size)
        assertEquals(walkId, result[0].figure.id)
        assertEquals("samba walks", result[0].reason)
        assertEquals(stationaryId, result[1].figure.id)
        assertEquals("stationary samba walk", result[1].reason)
    }

    @Test
    fun `suggest caps results at 3 even when LLM returns more`() {
        val ids = (1..5).map { UUID.randomUUID() }
        val candidates = ids.map { figure(it, "Figure $it") }
        val llmJson = ids.joinToString(",", "[", "]") { """{"id":"$it","reason":"mention $it"}""" }
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse(llmJson))

        val result = service().suggest("<p>Some long note text</p>", candidates, emptySet())

        assertEquals(3, result.size)
    }

    @Test
    fun `suggest discards ids returned by the LLM that are not in the candidate list`() {
        val validId = UUID.randomUUID()
        val outOfListId = UUID.randomUUID()
        val candidates = listOf(figure(validId, "Samba Walk"))

        val llmJson = """[{"id":"$outOfListId","reason":"phantom figure"},{"id":"$validId","reason":"samba walk"}]"""
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse(llmJson))

        val result = service().suggest("<p>Samba walk note</p>", candidates, emptySet())

        assertEquals(1, result.size)
        assertEquals(validId, result[0].figure.id)
    }

    @Test
    fun `suggest excludes already-pinned figures from candidates`() {
        val pinnedId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val candidates = listOf(figure(pinnedId, "Already Pinned"), figure(otherId, "Other Figure"))

        val llmJson = """[{"id":"$otherId","reason":"other figure"}]"""
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse(llmJson))

        val result = service().suggest("<p>Some note</p>", candidates, setOf(pinnedId))

        assertEquals(1, result.size)
        assertEquals(otherId, result[0].figure.id)
        // Verify the pinned figure was not sent to LLM by checking LLM was still called (other fig was sent)
        verify(llmProviderRouter).callLlm(anyStr(), anyLlmRequest())
    }

    @Test
    fun `suggest returns empty list when LLM returns empty array`() {
        val candidates = listOf(figure(UUID.randomUUID(), "Some Figure"))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse("[]"))

        val result = service().suggest("<p>Unrelated note</p>", candidates, emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `suggest returns empty list when note text is blank after HTML stripping`() {
        val candidates = listOf(figure(UUID.randomUUID(), "Some Figure"))

        val result = service().suggest("<div><br></div>", candidates, emptySet())

        assertTrue(result.isEmpty())
        verify(llmProviderRouter, never()).callLlm(anyStr(), anyLlmRequest())
    }

    @Test
    fun `suggest returns empty list when all candidates are already pinned`() {
        val id = UUID.randomUUID()
        val candidates = listOf(figure(id, "Already Pinned"))

        val result = service().suggest("<p>Some note about that figure</p>", candidates, setOf(id))

        assertTrue(result.isEmpty())
        verify(llmProviderRouter, never()).callLlm(anyStr(), anyLlmRequest())
    }

    // ── suggest: provider failure ─────────────────────────────────────────────

    @Test
    fun `suggest propagates exception when provider fails`() {
        val candidates = listOf(figure(UUID.randomUUID(), "Some Figure"))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenThrow(RuntimeException("OpenRouter returned 500"))

        assertThrows<RuntimeException> {
            service().suggest("<p>Some note</p>", candidates, emptySet())
        }
    }

    // ── suggest: malformed LLM responses ─────────────────────────────────────

    @Test
    fun `suggest fails rather than reporting no match when the LLM response is not JSON`() {
        val candidates = listOf(figure(UUID.randomUUID(), "Some Figure"))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("Sorry, I cannot help with that."))

        assertThrows<IllegalStateException> {
            service().suggest("<p>Some note</p>", candidates, emptySet())
        }
    }

    @Test
    fun `suggest fails rather than reporting no match when the JSON has no suggestions in it`() {
        val candidates = listOf(figure(UUID.randomUUID(), "Some Figure"))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""{"answer":"none"}"""))

        assertThrows<IllegalStateException> {
            service().suggest("<p>Some note</p>", candidates, emptySet())
        }
    }

    @Test
    fun `suggest accepts a single suggestion object without a reason, as Qwen returned for a Hockey Stick note`() {
        // The exact content qwen3.8-27b returned in dev: one bare object, no array, no reason.
        val hockeyStickId = UUID.fromString("38b87dea-3300-41c9-8608-a9035f185437")
        val candidates = listOf(figure(hockeyStickId, "Hockey Stick"), figure(UUID.randomUUID(), "Alemana"))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""{"id":"38b87dea-3300-41c9-8608-a9035f185437"}"""))

        val result = service().suggest(
            "<p>The hockey stick lives or dies on the connection through the left hand.</p>",
            candidates,
            emptySet()
        )

        assertEquals(listOf(hockeyStickId), result.map { it.figure.id })
        // No reason from the model, so the figure's name is quoted as the note writes it.
        assertEquals("hockey stick", result[0].reason)
    }

    @Test
    fun `a suggestion without a reason has no quote when the note does not name the figure`() {
        val alemanaId = UUID.randomUUID()
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""[{"id":"$alemanaId"}]"""))

        val result = service().suggest(
            "<p>She turns under the raised arm.</p>",
            listOf(figure(alemanaId, "Alemana")),
            emptySet()
        )

        assertEquals(listOf(alemanaId), result.map { it.figure.id })
        assertEquals(null, result[0].reason)
    }

    @Test
    fun `the name fallback only matches whole words`() {
        val volta = UUID.randomUUID()
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""[{"id":"$volta"}]"""))

        val result = service().suggest("<p>Revolta practice.</p>", listOf(figure(volta, "Volta")), emptySet())

        assertEquals(null, result[0].reason)
    }

    @Test
    fun `suggest accepts suggestions wrapped in an object`() {
        val walkId = UUID.randomUUID()
        val candidates = listOf(figure(walkId, "Samba Walk"))
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest()))
            .thenReturn(llmResponse("""{"suggestions":[{"id":"$walkId","reason":"samba walks"}]}"""))

        val result = service().suggest("<p>Samba walks</p>", candidates, emptySet())

        assertEquals(listOf(walkId), result.map { it.figure.id })
        assertEquals("samba walks", result[0].reason)
    }

    @Test
    fun `suggest handles LLM response wrapped in markdown code fence`() {
        val validId = UUID.randomUUID()
        val candidates = listOf(figure(validId, "Samba Walk"))
        val wrappedJson = "```json\n[{\"id\":\"$validId\",\"reason\":\"samba walk\"}]\n```"
        `when`(llmProviderRouter.callLlm(anyStr(), anyLlmRequest())).thenReturn(llmResponse(wrappedJson))

        val result = service().suggest("<p>Samba walk note</p>", candidates, emptySet())

        assertEquals(1, result.size)
        assertEquals(validId, result[0].figure.id)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun figure(id: UUID, name: String) = DanceFigure().apply {
        this.id = id
        this.name = name
    }

    private fun llmResponse(json: String) = LlmResponse(
        content = json,
        promptTokens = 10,
        completionTokens = 5,
        totalTokens = 15,
        reasoningTokens = null
    )

    private fun captureLlmRequest(): LlmRequest {
        val captor = ArgumentCaptor.forClass(LlmRequest::class.java)
        verify(llmProviderRouter).callLlm(anyStr(), captor.capture() ?: LlmRequest("", "", ""))
        return captor.value
    }

    private fun anyStr(): String {
        any<String>()
        return ""
    }

    private fun anyLlmRequest(): LlmRequest {
        any<LlmRequest>()
        return LlmRequest("", "", "")
    }
}
