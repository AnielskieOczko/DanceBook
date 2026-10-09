package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.ResultCard
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/**
 * The assistant's read tools. Every one goes through a service, so the access rules apply to
 * the assistant exactly as they do to the pages, and none of them throws: a bad argument or a
 * hidden item comes back as an empty [ToolResult] with a [ToolResult.message] the model can
 * read, because an exception would end the whole turn.
 *
 * Tools run on the request thread, where the security context and the open-in-view session live.
 */
@Component
@ConditionalOnAssistant
class AssistantReadTools(
    private val materialService: MaterialService,
    private val danceFigureService: DanceFigureService,
    private val danceTypeService: DanceTypeService,
    private val trainingEventService: TrainingEventService,
    private val appUserService: AppUserService,
    private val richTextService: RichTextService,
    private val clock: Clock,
    private val knowledgeChunkRepository: KnowledgeChunkRepository? = null,
    private val embeddingModel: EmbeddingModel? = null,
    private val choreographyRepository: ChoreographyRepository? = null,
    private val commentRepository: CommentRepository? = null,
    private val embeddingBudget: EmbeddingBudget? = null,
    private val assistantFeature: com.jankowski.rafal.dancebook.config.AssistantFeature? = null
) {

    companion object {
        const val MAX_RESULTS = 10
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val CREATED = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    }

    /** Query embeddings go through the shared budget; null (keyword-only) when it stays exhausted. */
    private fun embedQuery(text: String): FloatArray? {
        val model = embeddingModel ?: return null
        if (assistantFeature != null && !assistantFeature.enabled) return null
        if (embeddingBudget != null && !embeddingBudget.tryAcquireInteractive(appUserService.getCurrentUserOrNull()?.id)) return null
        return model.embed(text)
    }

    val callbacks: List<ToolCallback> by lazy { ToolCallbacks.from(this).toList() }

    @Tool(
        name = "search_figures",
        description = "Search the catalog of syllabus dance figures by a fragment of the figure's name, " +
            "for example 'heel turn'. Optionally narrow by dance style name (for example Waltz) and syllabus class letter (H to S). " +
            "Returns at most 10 figures."
    )
    fun searchFigures(
        @ToolParam(description = "A word or phrase from the figure's name") query: String,
        @ToolParam(description = "Dance style name, for example Waltz or Cha Cha", required = false) danceType: String?,
        @ToolParam(description = "Syllabus class letter from H to S", required = false) danceClass: String?
    ): ToolResult {
        val typeIds = resolveStyle(danceType) ?: return unknownStyle(danceType!!)
        val cls = resolveClass(danceClass) ?: return ToolResult(0, emptyList(), "There is no syllabus class '$danceClass'. Use a letter from H to S.")

        val user = appUserService.getCurrentUserOrNull()
        if (knowledgeChunkRepository != null) {
            val queryEmbedding = try { embedQuery(query) } catch (e: Exception) { null }
            val searchResults = knowledgeChunkRepository.hybridSearch(
                query = query,
                queryEmbedding = queryEmbedding,
                sourceTypes = listOf(KnowledgeSourceType.FIGURE),
                currentUser = user,
                limit = 20
            )
            if (searchResults.isNotEmpty()) {
                val figureMap = danceFigureService.findAll(typeIds.takeIf { it.isNotEmpty() }, null, cls.value, null, null, null)
                    .associateBy { it.id }
                val figureIds = searchResults.map { it.chunk.sourceId }.distinct()
                val found = figureIds.mapNotNull { figureMap[it] }
                if (found.isNotEmpty()) {
                    return ToolResult(found.size, found.take(MAX_RESULTS).map { figureCard(it) })
                }
            }
        }

        val found = danceFigureService.findAll(
            typeIds.takeIf { it.isNotEmpty() }, null, cls.value, query.trim(), null, null
        )
        return ToolResult(found.size, found.take(MAX_RESULTS).map { figureCard(it) })
    }

    @Tool(
        name = "search_notes",
        description = "Search the user's own notes (and public notes) by words in their title or text; every word must appear. " +
            "Optionally keep only notes that pin a given figure (by figure id) or that are in a given dance style. Returns at most 10 notes, newest first."
    )
    fun searchNotes(
        @ToolParam(description = "Words to look for in the note's title or text", required = false) query: String?,
        @ToolParam(description = "Id of a catalog figure; keeps only notes that pin it", required = false) figureId: String?,
        @ToolParam(description = "Dance style name, for example Waltz", required = false) danceType: String?
    ): ToolResult {
        val figure = figureId?.takeIf { it.isNotBlank() }?.let { parse(it) ?: return badId() }
        val typeIds = resolveStyle(danceType) ?: return unknownStyle(danceType!!)
        val terms = AssistantCards.terms(query)
        val user = appUserService.getCurrentUserOrNull()

        if (knowledgeChunkRepository != null && !query.isNullOrBlank()) {
            val queryEmbedding = try { embedQuery(query) } catch (e: Exception) { null }
            val searchResults = knowledgeChunkRepository.hybridSearch(
                query = query,
                queryEmbedding = queryEmbedding,
                sourceTypes = listOf(KnowledgeSourceType.NOTE, KnowledgeSourceType.NOTE_COMMENT),
                currentUser = user,
                limit = 20
            )
            val noteIds = searchResults.mapNotNull {
                when (it.chunk.sourceType) {
                    KnowledgeSourceType.NOTE -> it.chunk.sourceId
                    KnowledgeSourceType.NOTE_COMMENT -> commentRepository?.findById(it.chunk.sourceId)?.orElse(null)?.material?.id
                    else -> null
                }
            }.distinct()

            val fetched = noteIds.mapNotNull { id ->
                try { materialService.findById(id) } catch (e: Exception) { null }
            }.filter { note ->
                (figure == null || note.figures.any { it.danceFigure?.id == figure }) &&
                (typeIds.isEmpty() || note.danceType?.id in typeIds)
            }
            if (fetched.isNotEmpty()) {
                return ToolResult(fetched.size, fetched.take(MAX_RESULTS).map { noteCard(it, terms) })
            }
        }

        val notes = materialService.searchNotes(query?.trim(), figure, typeIds.firstOrNull(), MAX_RESULTS)
        return ToolResult(notes.size, notes.map { noteCard(it, terms) })
    }

    @Tool(
        name = "search_knowledge",
        description = "Search the knowledge base (notes, figure technical details, comments, and choreographies) for passages relevant to a question. Returns relevant passages with their title, source type, and link. Answers built from this tool must cite these sources as links, or state that nothing was found."
    )
    fun searchKnowledge(
        @ToolParam(description = "The question or topic to search for in knowledge and notes") question: String
    ): ToolResult {
        if (question.isBlank()) return ToolResult(0, emptyList(), "Please provide a question to search.")
        if (knowledgeChunkRepository == null) return ToolResult(0, emptyList(), "Nothing found in your notes.")

        val user = appUserService.getCurrentUserOrNull()
        val queryEmbedding = try { embedQuery(question) } catch (e: Exception) { null }
        val searchResults = knowledgeChunkRepository.hybridSearch(
            query = question,
            queryEmbedding = queryEmbedding,
            sourceTypes = null,
            currentUser = user,
            limit = 5
        )
        if (searchResults.isEmpty()) {
            return ToolResult(0, emptyList(), "Nothing found in your notes.")
        }

        val cards = searchResults.mapNotNull { res ->
            val chunk = res.chunk
            when (chunk.sourceType) {
                KnowledgeSourceType.NOTE -> {
                    val m = try { materialService.findById(chunk.sourceId) } catch (e: Exception) { null }
                    if (m != null) {
                        ResultCard(
                            kind = "note",
                            id = m.id.toString(),
                            title = m.name,
                            subtitle = listOfNotNull(m.danceType?.name, "Note").joinToString(" · "),
                            snippet = chunk.content,
                            url = "/materials/${m.id}"
                        )
                    } else null
                }
                KnowledgeSourceType.FIGURE -> {
                    val f = try { danceFigureService.findById(chunk.sourceId) } catch (e: Exception) { null }
                    if (f != null) {
                        ResultCard(
                            kind = "figure",
                            id = f.id.toString(),
                            title = f.name,
                            subtitle = listOfNotNull(f.danceType?.name, f.danceClass?.displayName, "Figure").joinToString(" · "),
                            snippet = chunk.content,
                            url = "/dance-figures/${f.id}"
                        )
                    } else null
                }
                KnowledgeSourceType.CHOREOGRAPHY -> {
                    val c = choreographyRepository?.findById(chunk.sourceId)?.orElse(null)
                    if (c != null) {
                        ResultCard(
                            kind = "choreography",
                            id = c.id.toString(),
                            title = c.name,
                            subtitle = listOfNotNull(c.danceType?.name, "Choreography").joinToString(" · "),
                            snippet = chunk.content,
                            url = "/choreographies/${c.id}",
                            chips = listOfNotNull(
                                c.medalLevel?.let { "Medal: ${it.displayName}" }
                            )
                        )
                    } else null
                }
                KnowledgeSourceType.NOTE_COMMENT -> {
                    val comment = commentRepository?.findById(chunk.sourceId)?.orElse(null)
                    val m = comment?.material
                    if (m != null) {
                        ResultCard(
                            kind = "note",
                            id = m.id.toString(),
                            title = m.name,
                            subtitle = "Comment on ${m.name}",
                            snippet = chunk.content,
                            url = "/materials/${m.id}"
                        )
                    } else null
                }
            }
        }

        return if (cards.isEmpty()) {
            ToolResult(0, emptyList(), "Nothing found in your notes.")
        } else {
            ToolResult(cards.size, cards)
        }
    }

    @Tool(
        name = "list_sessions",
        description = "List the user's training sessions between two dates (inclusive), oldest first, at most 10. " +
            "Set unconfirmedOnly to true to list only sessions that already happened and still need to be marked attended or skipped."
    )
    fun listSessions(
        @ToolParam(description = "First day, formatted yyyy-MM-dd") from: String,
        @ToolParam(description = "Last day, formatted yyyy-MM-dd") to: String,
        @ToolParam(description = "Only sessions waiting for confirmation", required = false) unconfirmedOnly: Boolean?
    ): ToolResult {
        val start = parseDate(from)
        val end = parseDate(to)
        if (start == null || end == null || end.isBefore(start)) {
            return ToolResult(0, emptyList(), "Dates must be real days formatted yyyy-MM-dd, with 'to' on or after 'from'.")
        }
        val user = appUserService.getCurrentUser()
        val now = java.time.LocalDateTime.now(clock)
        val sessions = trainingEventService.findInRange(start.atStartOfDay(), end.plusDays(1).atStartOfDay(), null)
            .filter { unconfirmedOnly != true || it.isAwaitingConfirmationFor(user, now) }
            .sortedBy { it.startTime }
        return ToolResult(sessions.size, sessions.take(MAX_RESULTS).map { sessionCard(it, it.isAwaitingConfirmationFor(user, now)) })
    }

    @Tool(name = "get_note", description = "Read one note by id, including the figures it pins.")
    fun getNote(@ToolParam(description = "The note's id") id: String): ToolResult {
        val uuid = parse(id) ?: return badId()
        return try {
            ToolResult(1, listOf(noteCard(materialService.findById(uuid), emptyList())))
        } catch (e: EntityNotFoundException) {
            ToolResult(0, emptyList(), "No note with that id is available.")
        } catch (e: AccessDeniedException) {
            ToolResult(0, emptyList(), "No note with that id is available.")
        }
    }

    @Tool(name = "get_figure", description = "Read one catalog figure by id.")
    fun getFigure(@ToolParam(description = "The figure's id") id: String): ToolResult {
        val uuid = parse(id) ?: return badId()
        return try {
            ToolResult(1, listOf(figureCard(danceFigureService.findById(uuid))))
        } catch (e: EntityNotFoundException) {
            ToolResult(0, emptyList(), "No figure with that id exists.")
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Three outcomes for the class argument: none given (value null), valid, or unknown (resolveClass returns null). */
    private data class ClassBox(val value: DanceClass?)

    private fun parse(id: String): UUID? = try { UUID.fromString(id.trim()) } catch (e: IllegalArgumentException) { null }

    private fun parseDate(text: String): LocalDate? = try { LocalDate.parse(text.trim()) } catch (e: DateTimeParseException) { null }

    private fun badId() = ToolResult(0, emptyList(), "That id is not valid. Ids come from earlier tool results.")

    private fun unknownStyle(name: String) = ToolResult(0, emptyList(), "There is no dance style called '$name'.")

    /** Empty list = no style filter; null = a style was named but does not exist. */
    private fun resolveStyle(name: String?): List<UUID>? {
        if (name.isNullOrBlank()) return emptyList()
        val matches = danceTypeService.findAll().filter { it.name.equals(name.trim(), ignoreCase = true) }.mapNotNull { it.id }
        return matches.takeIf { it.isNotEmpty() }
    }

    private fun resolveClass(letter: String?): ClassBox? {
        if (letter.isNullOrBlank()) return ClassBox(null)
        val cleaned = letter.trim().removePrefix("Class").removePrefix("class").trim()
        return DanceClass.entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }?.let { ClassBox(it) }
    }

    private fun figureCard(f: DanceFigure) = ResultCard(
        kind = "figure",
        id = f.id.toString(),
        title = f.name,
        subtitle = listOfNotNull(f.danceType?.name, f.danceClass?.displayName).joinToString(" · ").ifEmpty { null },
        url = "/dance-figures/${f.id}",
        chips = listOfNotNull(
            f.alternativeTiming?.takeIf { it.isNotBlank() },
            f.medalLevel?.let { "Medal: ${it.displayName}" }
        )
    )

    private fun noteCard(m: Material, terms: List<String>): ResultCard {
        val plain = richTextService.toPlainText(m.description)
        return ResultCard(
            kind = "note",
            id = m.id.toString(),
            title = m.name,
            subtitle = listOfNotNull(m.danceType?.name, m.createdAt.format(CREATED)).joinToString(" · "),
            snippet = AssistantCards.snippet(plain, terms),
            url = "/materials/${m.id}",
            chips = m.figures.mapNotNull { pin ->
                pin.danceFigure?.let { df ->
                    listOfNotNull(df.name, df.alternativeTiming?.takeIf { it.isNotBlank() }).joinToString(" · ")
                }
            }
        )
    }

    private fun sessionCard(e: TrainingEvent, toConfirm: Boolean) = ResultCard(
        kind = "session",
        id = e.id.toString(),
        title = e.title,
        subtitle = "${e.startTime.format(DAY)} · ${e.startTime.format(TIME)}–${e.endTime.format(TIME)}",
        url = "/training-events/${e.id}",
        chips = e.segments.mapNotNull { s -> s.danceCategory?.let { "${it.name} ${s.durationMinutes}m" } } +
            (if (toConfirm) listOf("To confirm") else emptyList())
    )
}
