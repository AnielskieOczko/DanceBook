package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jankowski.rafal.dancebook.dto.DanceFigureLinkRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureStepRequest
import com.jankowski.rafal.dancebook.dto.GuidedParseResult
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Service
class GuidedFigureParseService(
    private val openRouterService: OpenRouterService,
    private val danceFigureRepository: DanceFigureRepository,
    private val danceTypeRepository: DanceTypeRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(GuidedFigureParseService::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun parseFromJson(jsonString: String): GuidedParseResult {
        return try {
            val dto = objectMapper.readValue<SyllabusImporterService.AiParsedFigureDto>(jsonString)
            val request = mapToRequest(dto, null)
            GuidedParseResult(success = true, request = request)
        } catch (e: Exception) {
            log.error("Failed to parse dance figure from pasted JSON", e)
            GuidedParseResult(
                success = false,
                errors = listOf("Invalid JSON structure: ${e.message}")
            )
        }
    }

    fun parseFromUrl(url: String, model: String, danceTypeId: UUID?): GuidedParseResult {
        log.info("Starting guided parse from URL: {} using model: {}", url, model)
        val htmlContent = try {
            fetchUrlContent(url)
        } catch (e: Exception) {
            log.error("Failed to fetch content from URL: {}", url, e)
            return GuidedParseResult(
                success = false,
                errors = listOf("Failed to load webpage: ${e.message}")
            )
        }

        val extractedText = try {
            Jsoup.parse(htmlContent).text()
        } catch (e: Exception) {
            log.error("Failed to extract clean text using Jsoup from URL: {}", url, e)
            return GuidedParseResult(
                success = false,
                errors = listOf("Failed to parse webpage content: ${e.message}")
            )
        }

        if (extractedText.isBlank()) {
            return GuidedParseResult(
                success = false,
                errors = listOf("Webpage text content is empty or unreadable.")
            )
        }

        val baseSystemPrompt = try {
            val promptFile = File("docs/figures steps/AGENT_PROMPT.md")
            if (promptFile.exists()) promptFile.readText() else ""
        } catch (e: Exception) {
            log.warn("Could not load base agent prompt template", e)
            ""
        }

        val existingFigures = danceFigureRepository.findAll()
        val referenceFiguresText = existingFigures.joinToString("\n") { "- ${it.name} (${it.danceType?.name ?: "Unknown"})" }

        val systemPrompt = """
            $baseSystemPrompt
            
            ## Authoritative Figure Names Reference
            Below is the complete list of standard figure names currently stored in the database, along with their respective dance styles. You MUST use these exact names when standardizing figures in your output (case-insensitive, ignoring minor formatting differences):
            $referenceFiguresText
        """.trimIndent()

        val userPrompt = objectMapper.writeValueAsString(mapOf(
            "url" to url,
            "text" to extractedText
        ))

        return try {
            val llmResponse = openRouterService.callLlm(systemPrompt, userPrompt, model)
            val dto = objectMapper.readValue<SyllabusImporterService.AiParsedFigureDto>(llmResponse)
            val request = mapToRequest(dto, danceTypeId)
            
            // Ensure the source URL is included in the links list if not already present
            val finalRequest = if (request.links.none { it.url.trim() == url.trim() }) {
                val updatedLinks = request.links.toMutableList()
                updatedLinks.add(
                    DanceFigureLinkRequest(
                        url = url,
                        title = "DanceCentral Reference",
                        type = "syllabus"
                    )
                )
                request.copy(links = updatedLinks)
            } else {
                request
            }

            GuidedParseResult(success = true, request = finalRequest)
        } catch (e: Exception) {
            log.error("Failed to parse URL content via LLM", e)
            GuidedParseResult(
                success = false,
                errors = listOf("AI parsing failed: ${e.message}")
            )
        }
    }

    open fun fetchUrlContent(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("HTTP Status ${response.statusCode()}")
        }
        return response.body()
    }

    private fun mapToRequest(dto: SyllabusImporterService.AiParsedFigureDto, defaultDanceTypeId: UUID?): DanceFigureRequest {
        val danceTypes = danceTypeRepository.findAll()
        val dbDanceTypeName = dto.dance_type?.let { mapDanceTypeJsonToDbName(it) }
        val danceType = danceTypes.find { it.name.equals(dbDanceTypeName, ignoreCase = true) }
            ?: danceTypes.find { it.id == defaultDanceTypeId }

        val danceClass = mapLevelToDanceClass(dto.level)

        val steps = dto.steps?.mapIndexed { index, stepDto ->
            val sn = stepDto.step_number
            val parsedStepNum = when (sn) {
                is Number -> sn.toInt()
                is String -> sn.substringBefore("&").substringBefore(" ").trim().toIntOrNull() ?: (index + 1)
                else -> index + 1
            }
            DanceFigureStepRequest(
                stepNumber = parsedStepNum,
                timing = stepDto.timing ?: "",
                role = stepDto.role?.uppercase() ?: "LEADER",
                foot = stepDto.foot?.uppercase() ?: "",
                action = stepDto.action ?: "",
                footwork = stepDto.footwork,
                alignment = stepDto.alignment,
                amountOfTurn = stepDto.amount_of_turn,
                commentsText = stepDto.comments?.joinToString("\n")
            )
        }?.toMutableList() ?: mutableListOf()

        val links = mutableListOf<DanceFigureLinkRequest>()
        val urls = mutableListOf<String>()
        if (dto.urls != null) urls.addAll(dto.urls)
        if (dto.url != null && !urls.contains(dto.url)) urls.add(dto.url)

        urls.forEach { url ->
            links.add(
                DanceFigureLinkRequest(
                    url = url,
                    title = "DanceCentral Reference",
                    type = "syllabus"
                )
            )
        }

        return DanceFigureRequest(
            name = dto.name ?: "",
            danceTypeId = danceType?.id,
            danceClass = danceClass,
            startingFootLeader = dto.starting_foot_leader,
            endingFootLeader = dto.ending_foot_leader,
            startingFootFollower = dto.starting_foot_follower,
            endingFootFollower = dto.ending_foot_follower,
            startingPosition = dto.starting_position,
            endingPosition = dto.ending_position,
            precedingFigureNames = parseListOrStringField(dto.preceding_figure_names),
            followingFigureNames = parseListOrStringField(dto.following_figure_names),
            notes = dto.notes,
            steps = steps,
            links = links
        )
    }

    private fun mapLevelToDanceClass(level: String?): DanceClass {
        return when (level?.lowercase()) {
            "newcomer", "bronze" -> DanceClass.H
            "silver" -> DanceClass.F
            "gold" -> DanceClass.E
            else -> DanceClass.H
        }
    }

    private fun mapDanceTypeJsonToDbName(jsonDanceType: String): String {
        return when (jsonDanceType.uppercase()) {
            "WALTZ" -> "Waltz"
            "TANGO" -> "Tango"
            "VIENNESE_WALTZ" -> "Viennese Waltz"
            "SLOW_FOXTROT", "FOXTROT" -> "Foxtrot"
            "QUICKSTEP" -> "Quickstep"
            "CHA_CHA_CHA", "CHA_CHA" -> "Cha Cha"
            "SAMBA" -> "Samba"
            "RUMBA" -> "Rumba"
            "PASO_DOBLE" -> "Paso Doble"
            "JIVE" -> "Jive"
            else -> jsonDanceType
        }
    }

    private fun parseListOrStringField(field: Any?): List<String> {
        if (field == null) return emptyList()
        if (field is List<*>) {
            return field.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        }
        if (field is String) {
            return field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }
}
