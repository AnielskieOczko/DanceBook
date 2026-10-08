package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftField
import com.jankowski.rafal.dancebook.dto.DraftFigureLine
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Builds what a draft card shows from the draft's payload. Names are looked up through the
 * services, as the viewer, and a lookup that fails (the thing was deleted, or is hidden from
 * this user) leaves that line off the card: a card must always render.
 */
@Component
@ConditionalOnAssistant
class AssistantDraftViews(
    private val codec: AssistantDraftCodec,
    private val richTextService: RichTextService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val danceFigureService: DanceFigureService,
    private val materialService: MaterialService,
    private val trainingEventService: TrainingEventService
) {

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /** Where a saved draft's card links to. */
        fun urlFor(kind: DraftKind, id: UUID): String = when (kind) {
            DraftKind.NOTE -> "/materials/$id"
            DraftKind.TRAINING_EVENT -> "/training-events/$id"
            DraftKind.FIGURE -> "/dance-figures/$id"
        }
    }

    fun build(draft: AssistantDraft): DraftView {
        val savedUrl = draft.savedEntityId?.takeIf { draft.status == DraftStatus.SAVED }?.let { urlFor(draft.kind, it) }
        val (heading, fields, figures) = when (draft.kind) {
            DraftKind.NOTE -> note(codec.read(draft.payload, MaterialRequest::class.java))
            DraftKind.TRAINING_EVENT -> session(codec.read(draft.payload, TrainingEventRequest::class.java))
            DraftKind.FIGURE -> figure(codec.read(draft.payload, DanceFigureRequest::class.java))
        }
        return DraftView(draft.id!!, draft.kind, draft.status, heading, fields, figures, savedUrl, draft.notice)
    }

    private fun note(r: MaterialRequest): Triple<String, List<DraftField>, List<DraftFigureLine>> {
        val fields = mutableListOf<DraftField>()
        plain(r.description)?.let { fields += DraftField("Text", it) }
        r.danceTypeId?.let { id -> lookup { danceTypeService.findById(id).name } }?.let { fields += DraftField("Dance style", it) }
        r.trainingEventId?.let { id -> lookup { trainingEventService.findById(id).title } }?.let { fields += DraftField("Session", it) }
        if (r.markAttended) fields += DraftField("Attendance", "Mark the session as attended")
        r.videoLink?.let { fields += DraftField("Video", it) }
        val figures = r.figureIds.mapNotNull { id ->
            lookup { danceFigureService.findById(id) }?.let {
                DraftFigureLine(it.name, it.alternativeTiming?.takeIf { timing -> timing.isNotBlank() }, "/dance-figures/$id")
            }
        }
        return Triple(r.name, fields, figures)
    }

    private fun session(r: TrainingEventRequest): Triple<String, List<DraftField>, List<DraftFigureLine>> {
        val fields = mutableListOf<DraftField>()
        r.date?.let { fields += DraftField("Date", it.format(DAY)) }
        if (r.startTime != null && r.endTime != null) {
            fields += DraftField("Time", "${r.startTime.format(TIME)}–${r.endTime.format(TIME)}")
        }
        fields += DraftField("Type", r.eventType.lowercase().replaceFirstChar { it.uppercase() })
        val styles = r.segments.mapNotNull { s ->
            val id = s.categoryId ?: return@mapNotNull null
            lookup { danceCategoryService.findById(id).name }?.let { name -> "$name ${s.durationMinutes ?: 0}m" }
        }
        if (styles.isNotEmpty()) fields += DraftField("Styles", styles.joinToString(", "))
        r.materialId?.let { id -> lookup { materialService.findById(id).name } }?.let { fields += DraftField("Linked note", it) }
        plain(r.description)?.let { fields += DraftField("Notes", it) }
        return Triple(r.title, fields, emptyList())
    }

    private fun figure(r: DanceFigureRequest): Triple<String, List<DraftField>, List<DraftFigureLine>> {
        val fields = mutableListOf<DraftField>()
        r.danceTypeId?.let { id -> lookup { danceTypeService.findById(id).name } }?.let { fields += DraftField("Dance style", it) }
        r.danceClass?.let { fields += DraftField("Class", it.displayName) }
        r.medalLevel?.let { fields += DraftField("Medal", it.displayName) }
        r.alternativeTiming?.takeIf { it.isNotBlank() }?.let { fields += DraftField("Timing", it) }
        r.startingPosition?.takeIf { it.isNotBlank() }?.let { fields += DraftField("Starts", it) }
        r.endingPosition?.takeIf { it.isNotBlank() }?.let { fields += DraftField("Ends", it) }
        plain(r.notes)?.let { fields += DraftField("Notes", it) }
        return Triple(r.name, fields, emptyList())
    }

    private fun plain(html: String?): String? = richTextService.toPlainText(html)?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> lookup(block: () -> T): T? = try { block() } catch (e: RuntimeException) { null }
}
