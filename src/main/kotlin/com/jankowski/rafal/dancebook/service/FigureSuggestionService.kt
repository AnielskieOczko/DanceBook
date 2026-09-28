package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.OpenRouterProperties
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Suggests catalog figures that a note explicitly discusses.
 *
 * Algorithm:
 * 1. Build a candidate list: figures in the note's style (or all figures if none),
 *    minus any already pinned.
 * 2. Ask the LLM which candidates the note text explicitly discusses. The LLM returns
 *    a JSON array of {id, reason} objects, where `reason` quotes the phrase from the
 *    note that matched.
 * 3. Discard any ids not in the candidate set, cap at 3, return.
 */
@Service
class FigureSuggestionService(
    private val llmProviderRouter: LlmProviderRouter,
    private val openRouterProperties: OpenRouterProperties,
    private val danceFigureService: DanceFigureService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(FigureSuggestionService::class.java)

    /** True when an LLM provider is configured and can be used for suggestions. */
    fun isAvailable(): Boolean = openRouterProperties.apiKey.isNotBlank()

    /**
     * Suggest figures for [material]: the candidates are the catalog figures in the note's
     * dance style, or every figure when the note has no style, minus those already pinned.
     */
    fun suggestForMaterial(material: Material): List<FigureSuggestion> {
        val text = material.description
        if (text.isNullOrBlank()) return emptyList()
        val danceTypeId = material.danceType?.id
        val candidates = danceFigureService.findAll(
            typeIds = danceTypeId?.let { listOf(it) },
            sortBy = "name_asc"
        )
        val pinnedIds = material.figures.mapNotNull { it.danceFigure?.id }.toSet()
        return suggest(text, candidates, pinnedIds)
    }

    /**
     * Suggest up to 3 figures from [candidates] that the [noteText] explicitly discusses.
     * [pinnedIds] are already pinned — they must never appear in the result.
     *
     * The note text may be HTML (rich text); it is stripped to plain text before the LLM sees it.
     *
     * Returns an empty list when nothing clearly matches.
     * Throws on provider failure.
     */
    fun suggest(
        noteText: String,
        candidates: List<DanceFigure>,
        pinnedIds: Set<UUID>
    ): List<FigureSuggestion> {
        val eligibleCandidates = candidates.filter { it.id != null && it.id !in pinnedIds }
        if (eligibleCandidates.isEmpty()) {
            log.debug("No eligible candidates after excluding already-pinned figures")
            return emptyList()
        }

        val plainText = Jsoup.parse(noteText).text()
        if (plainText.isBlank()) {
            log.debug("Note text is empty after stripping HTML")
            return emptyList()
        }

        val candidateMap = eligibleCandidates
            .filter { it.id != null }
            .associate { it.id!! to it }

        val candidateListForPrompt = candidateMap.entries
            .joinToString("\n") { (id, fig) -> "- id=$id name=\"${fig.name}\"" }

        val systemPrompt = """
            You are a ballroom-dance teaching assistant.
            
            The user will give you a note from a dance class and a list of catalog figures.
            Your job is to identify which figures from the catalog the note EXPLICITLY discusses —
            meaning the note's text clearly refers to that figure by name or unmistakable description.
            
            Rules:
            - Only return figures whose names appear verbatim or nearly verbatim in the note.
            - Do not guess or infer from vague context.
            - If nothing matches clearly, return an empty array.
            - Return at most 3 matches, prioritising the closest matches first.
            - For each match, quote the exact short phrase (2–6 words) from the note that matched.
            
            Respond with a JSON array only, no other text. Each element:
            {"id":"<uuid from the list>","reason":"<quoted phrase from the note>"}
            
            If nothing matches:
            []
        """.trimIndent()

        val userPrompt = """
            Note text:
            $plainText
            
            Catalog figures:
            $candidateListForPrompt
        """.trimIndent()

        log.info("Requesting figure suggestions via LLM for note ({} chars), {} candidates",
            plainText.length, candidateMap.size)

        val llmResponse = llmProviderRouter.callLlm(
            provider = "openrouter",
            request = LlmRequest(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                model = openRouterProperties.figureSuggestionModel,
                maxTokens = 512,
                temperature = 0.1,
                extras = mapOf("providerOnly" to openRouterProperties.figureSuggestionProviders)
            )
        )

        return parseSuggestions(llmResponse.content, candidateMap)
    }

    private fun parseSuggestions(
        content: String,
        candidateMap: Map<UUID, DanceFigure>
    ): List<FigureSuggestion> {
        val trimmed = content.trim()
        // Strip markdown code fences if the model wraps its response
        val jsonText = if (trimmed.startsWith("```")) {
            trimmed.lines().drop(1).dropLast(1).joinToString("\n")
        } else {
            trimmed
        }

        val arrayNode = try {
            val node = objectMapper.readTree(jsonText)
            if (!node.isArray) {
                log.warn("LLM returned non-array JSON for figure suggestions: {}", jsonText.take(200))
                return emptyList()
            }
            node
        } catch (e: Exception) {
            log.warn("Could not parse LLM figure suggestion response as JSON: {}", jsonText.take(200))
            return emptyList()
        }

        val results = mutableListOf<FigureSuggestion>()
        for (elem in arrayNode) {
            if (results.size >= 3) break
            val idStr = elem.get("id")?.asText()?.trim() ?: continue
            val reason = elem.get("reason")?.asText()?.trim() ?: continue
            val id = try { UUID.fromString(idStr) } catch (_: IllegalArgumentException) { continue }
            val figure = candidateMap[id] ?: continue  // discard out-of-list ids
            results += FigureSuggestion(figure = figure, reason = reason)
        }

        log.info("Figure suggestions: {} returned, {} valid after filtering", arrayNode.size(), results.size)
        return results
    }
}

/**
 * One figure suggestion returned by [FigureSuggestionService.suggest].
 *
 * @param figure The catalog figure being suggested.
 * @param reason A short phrase quoted from the note that matched this figure.
 */
data class FigureSuggestion(
    val figure: DanceFigure,
    val reason: String
)
