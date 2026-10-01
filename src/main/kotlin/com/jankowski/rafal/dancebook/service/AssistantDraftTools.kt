package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.TrainingEventType
import jakarta.validation.Validator
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** One style row of a drafted session. A class with defaults, so Spring AI can build it from the model's JSON. */
class DraftSegmentArg {
    var category: String = ""
    var minutes: Int = 0
}

/**
 * The assistant's draft tools. **None of them writes to a domain table**: each checks the
 * arguments, builds the same request DTO the form binds, validates it with the forms' Bean
 * Validation, and stores it as a pending draft. Only the card's Save writes, and it goes through
 * the services (see [AssistantDraftService.save]).
 *
 * Like the read tools, none throws: a problem comes back as a [ToolResult] message the model can
 * act on, because an exception would end the whole turn. A draft that fails validation may be
 * retried once; the second failure tells the model to ask the user instead.
 */
@Component
@ConditionalOnAssistant
class AssistantDraftTools(
    private val turns: AssistantTurnScope,
    private val grounding: AssistantGrounding,
    private val drafts: AssistantDraftService,
    private val codec: AssistantDraftCodec,
    private val validator: Validator,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val activeCalendarService: ActiveCalendarService
) {

    companion object {
        private const val NOTE = "draft_note"
        private const val SESSION = "draft_training_event"
        private const val FIGURE = "draft_figure"
        private const val MAX_ATTEMPTS = 2
    }

    val callbacks: List<ToolCallback> by lazy { ToolCallbacks.from(this).toList() }

    @Tool(
        name = NOTE,
        description = "Draft a new note for the user to review. Nothing is saved until the user presses Save on the card. " +
            "Every id must come from an earlier search_figures, search_notes, list_sessions or get_* result in this conversation; " +
            "an id you did not get from a tool is dropped. To wrap up a session, pass its sessionId and markAttended true."
    )
    fun draftNote(
        @ToolParam(description = "Short title of the note, at least 2 characters") title: String,
        @ToolParam(description = "The note's text as plain text; blank lines separate paragraphs", required = false) text: String?,
        @ToolParam(description = "Dance style name, for example Waltz", required = false) danceStyle: String?,
        @ToolParam(description = "Ids of catalog figures to pin, taken from search_figures or get_figure results", required = false) figureIds: List<String>?,
        @ToolParam(description = "Id of the training session the note is about, taken from list_sessions results", required = false) sessionId: String?,
        @ToolParam(description = "Mark that session as attended. Needs sessionId", required = false) markAttended: Boolean?,
        @ToolParam(description = "A video link for the note", required = false) videoLink: String?
    ): ToolResult {
        val turn = turns.current() ?: return unavailable()
        val grounded = grounding.idsFor(turn)
        val style = findStyle(danceStyle)
        if (style is Style.Unknown) return fail(turn, NOTE, listOf(style.problem))

        val dropped = mutableListOf<String>()
        val pins = figureIds.orEmpty().mapNotNull { keep(it, "figure", grounded.figures, dropped) }.distinct()
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { keep(it, "session", grounded.sessions, dropped) }
        val attend = markAttended == true && session != null
        if (markAttended == true && session == null) dropped += "markAttended (it needs a valid sessionId)"

        val type = (style as? Style.Found)?.type
        val request = MaterialRequest(
            name = title.trim(),
            description = AssistantText.toRichText(text),
            danceCategoryId = type?.category?.id,
            danceTypeId = type?.id,
            videoLink = videoLink?.trim()?.takeIf { it.isNotEmpty() },
            version = 0,
            trainingEventId = session,
            figureIds = pins,
            markAttended = attend
        )
        return store(turn, DraftKind.NOTE, NOTE, request, dropped)
    }

    @Tool(
        name = SESSION,
        description = "Draft a new training session for the user to review; it is added to their Google Calendar only when they press Save. " +
            "For one session only, not a repeating series. The times are local. A note to link must come from an earlier tool result."
    )
    fun draftTrainingEvent(
        @ToolParam(description = "Title of the session, for example Practice") title: String,
        @ToolParam(description = "Day, formatted yyyy-MM-dd") date: String,
        @ToolParam(description = "Start time, formatted HH:mm (24 hours)") startTime: String,
        @ToolParam(description = "End time on the same day, formatted HH:mm, after the start") endTime: String,
        @ToolParam(description = "TRAINING, CAMP, COMPETITION, WORKSHOP or OTHER. Defaults to TRAINING", required = false) eventType: String?,
        @ToolParam(description = "How the session splits across dance categories, for example Standard 60 minutes then Latin 30", required = false) segments: List<DraftSegmentArg>?,
        @ToolParam(description = "Free text about the session, for example the dances planned", required = false) description: String?,
        @ToolParam(description = "Id of a note to link to the session, taken from search_notes or get_note results", required = false) noteId: String?
    ): ToolResult {
        val turn = turns.current() ?: return unavailable()
        val grounded = grounding.idsFor(turn)

        val day = parseDate(date) ?: return fail(turn, SESSION, listOf("date '$date' is not a real day; use yyyy-MM-dd"))
        val start = parseTime(startTime) ?: return fail(turn, SESSION, listOf("startTime '$startTime' is not a time; use HH:mm"))
        val end = parseTime(endTime) ?: return fail(turn, SESSION, listOf("endTime '$endTime' is not a time; use HH:mm"))
        if (!end.isAfter(start)) return fail(turn, SESSION, listOf("endTime must be after startTime on the same day"))

        val kind = eventType?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: TrainingEventType.TRAINING.name
        if (TrainingEventType.entries.none { it.name == kind }) {
            return fail(turn, SESSION, listOf("eventType '$eventType' is not valid; use one of ${TrainingEventType.entries.joinToString { it.name }}"))
        }

        val categories = danceCategoryService.findAll()
        val rows = mutableListOf<TrainingEventSegmentRequest>()
        for (segment in segments.orEmpty()) {
            val category = categories.firstOrNull { it.name.equals(segment.category.trim(), ignoreCase = true) }
                ?: return fail(turn, SESSION, listOf("there is no dance category '${segment.category}'; use one of ${categories.joinToString { it.name }}"))
            rows += TrainingEventSegmentRequest(category.id, segment.minutes)
        }

        val dropped = mutableListOf<String>()
        val note = noteId?.takeIf { it.isNotBlank() }?.let { keep(it, "note", grounded.notes, dropped) }

        val target = try {
            activeCalendarService.creationTarget()
        } catch (e: CalendarSyncException) {
            // Not something the model can fix by retrying, so it does not use up the retry.
            return ToolResult(0, emptyList(), "No draft was created: ${e.message}. Tell the user; they need to fix their calendar first.")
        }

        val request = TrainingEventRequest(
            title = title.trim(),
            date = day,
            startTime = start,
            endTime = end,
            eventType = kind,
            calendarId = target.id,
            segments = rows,
            description = AssistantText.toRichText(description),
            materialId = note
        )
        return store(turn, DraftKind.TRAINING_EVENT, SESSION, request, dropped)
    }

    @Tool(
        name = FIGURE,
        description = "Draft a new catalog figure for the user to review. Nothing is saved until the user presses Save. " +
            "Check with search_figures first that it is not already in the catalog."
    )
    fun draftFigure(
        @ToolParam(description = "The figure's name, for example Heel Turn") name: String,
        @ToolParam(description = "Dance style name, for example Waltz") danceStyle: String,
        @ToolParam(description = "Syllabus class letter from H to S", required = false) danceClass: String?,
        @ToolParam(description = "The timing, for example 1 2 3 or S Q Q", required = false) alternativeTiming: String?,
        @ToolParam(description = "Starting position, for example Facing diagonal wall", required = false) startingPosition: String?,
        @ToolParam(description = "Ending position", required = false) endingPosition: String?,
        @ToolParam(description = "Notes about the figure", required = false) notes: String?
    ): ToolResult {
        val turn = turns.current() ?: return unavailable()
        val style = findStyle(danceStyle)
        val type = (style as? Style.Found)?.type
            ?: return fail(turn, FIGURE, listOf((style as? Style.Unknown)?.problem ?: "danceStyle is required"))
        val cls = danceClass?.trim()?.takeIf { it.isNotEmpty() }?.let { letter ->
            DanceClass.entries.firstOrNull { it.name.equals(letter.removePrefix("Class").removePrefix("class").trim(), ignoreCase = true) }
                ?: return fail(turn, FIGURE, listOf("there is no syllabus class '$danceClass'; use a letter from H to S"))
        }

        val request = DanceFigureRequest(
            name = name.trim(),
            danceTypeId = type.id,
            danceClass = cls,
            alternativeTiming = alternativeTiming?.trim()?.takeIf { it.isNotEmpty() },
            startingPosition = startingPosition?.trim()?.takeIf { it.isNotEmpty() },
            endingPosition = endingPosition?.trim()?.takeIf { it.isNotEmpty() },
            notes = AssistantText.toRichText(notes)
        )
        return store(turn, DraftKind.FIGURE, FIGURE, request, emptyList())
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private sealed interface Style {
        data object None : Style
        data class Found(val type: DanceType) : Style
        data class Unknown(val problem: String) : Style
    }

    private fun findStyle(name: String?): Style {
        if (name.isNullOrBlank()) return Style.None
        val match = danceTypeService.findAll().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        return if (match != null) Style.Found(match) else Style.Unknown("there is no dance style called '${name.trim()}'")
    }

    /** The id, if the model's [raw] text is a UUID that a tool result in this conversation contained. Else it is noted in [dropped]. */
    private fun keep(raw: String, what: String, allowed: Set<UUID>, dropped: MutableList<String>): UUID? {
        val id = try { UUID.fromString(raw.trim()) } catch (e: IllegalArgumentException) { null }
        if (id == null || id !in allowed) {
            dropped += "the $what id '${raw.trim().take(40)}' (it did not come from a tool result in this conversation; search for it first)"
            return null
        }
        return id
    }

    private fun parseDate(text: String): LocalDate? = try { LocalDate.parse(text.trim()) } catch (e: DateTimeParseException) { null }

    private fun parseTime(text: String): LocalTime? = try { LocalTime.parse(text.trim()) } catch (e: DateTimeParseException) { null }

    private fun store(turn: ToolTurn, kind: DraftKind, tool: String, request: Any, dropped: List<String>): ToolResult {
        val problems = validator.validate(request).map { "${it.propertyPath}: ${it.message}" }.sorted()
        if (problems.isNotEmpty()) return fail(turn, tool, problems)
        val id = drafts.create(turn.conversationId, kind, codec.toPayload(request))
        val left = if (dropped.isEmpty()) "" else " Left out: ${dropped.joinToString("; ")}."
        return ToolResult(
            1, emptyList(),
            "Draft created. The user now sees it as a card with Save and Edit in form; nothing is saved yet. " +
                "Say so in one sentence and do not repeat the fields.$left",
            draftId = id.toString()
        )
    }

    private fun fail(turn: ToolTurn, tool: String, problems: List<String>): ToolResult {
        val attempts = turn.failures.merge(tool, 1, Int::plus) ?: 1
        val advice = if (attempts >= MAX_ATTEMPTS) {
            "Do not call $tool again. Tell the user what is missing or wrong and ask them."
        } else {
            "Fix these and call $tool once more."
        }
        return ToolResult(0, emptyList(), "No draft was created: ${problems.joinToString("; ")}. $advice")
    }

    private fun unavailable() = ToolResult(0, emptyList(), "Drafts are not available right now.")
}
