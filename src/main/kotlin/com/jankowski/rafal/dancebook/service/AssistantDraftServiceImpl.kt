package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DraftKind
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Save is **claim, execute, finish or release**, not one transaction. The training service writes
 * to Google Calendar, and in this codebase that I/O stays out of database transactions. So:
 *
 *  1. [AssistantDraftStore.claim] locks the draft row and moves it to `SAVED` in a short
 *     transaction of its own. A second Save, or a second tab, is refused here.
 *  2. The same service methods the form calls run. For a note the database writes (create, pins,
 *     attendance) share one transaction so they succeed or fail together; linking the session
 *     comes after, because it writes to Google, and, as on the form, its failure keeps the note.
 *  3. [AssistantDraftStore.finish] records what was created; or, if step 2 threw,
 *     [AssistantDraftStore.release] puts the draft back to `PENDING` with the error.
 *
 * A crash between 1 and 3 leaves a draft that reads as saved with no link. That is rare and
 * visible, and it never double-creates, which is the direction to err in.
 */
@Service
@ConditionalOnAssistant
class AssistantDraftServiceImpl(
    private val store: AssistantDraftStore,
    private val views: AssistantDraftViews,
    private val codec: AssistantDraftCodec,
    private val materialService: MaterialService,
    private val trainingEventService: TrainingEventService,
    private val danceFigureService: DanceFigureService,
    private val activeCalendarService: ActiveCalendarService,
    private val transactions: TransactionTemplate
) : AssistantDraftService {

    companion object {
        const val LINK_FAILED = "Saved, but the note could not be linked to the session. Open the session to attach it."
        const val GENERIC_FAILURE = "Could not save this draft. Try again, or use Edit in form."
    }

    private val log = LoggerFactory.getLogger(AssistantDraftServiceImpl::class.java)

    private data class Saved(val entityId: UUID, val notice: String?)

    override fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID =
        store.create(conversationId, kind, payload).id!!

    override fun attach(draftId: UUID, messageId: UUID) = store.attach(draftId, messageId)

    override fun view(id: UUID): DraftView = views.build(store.findOwned(id))

    override fun views(ids: Collection<UUID>): List<DraftView> = ids.mapNotNull {
        try { view(it) } catch (e: EntityNotFoundException) { null }
    }

    override fun save(id: UUID): DraftView {
        val draft = store.claim(id)
        try {
            val saved = when (draft.kind) {
                DraftKind.NOTE -> saveNote(draft)
                DraftKind.TRAINING_EVENT -> saveSession(draft)
                DraftKind.FIGURE -> saveFigure(draft)
            }
            store.finish(id, saved.entityId, saved.notice)
        } catch (e: RuntimeException) {
            store.release(id, problemText(e))
        }
        return view(id)
    }

    override fun openInForm(id: UUID): String {
        val draft = store.discardForForm(id)
        val path = when (draft.kind) {
            DraftKind.NOTE -> "/materials/new"
            DraftKind.TRAINING_EVENT -> "/training-events/new"
            DraftKind.FIGURE -> "/dance-figures/new"
        }
        return "$path?fromDraft=$id"
    }

    override fun noteForForm(id: UUID): MaterialRequest? = openDraft(id, DraftKind.NOTE, MaterialRequest::class.java)
    override fun sessionForForm(id: UUID): TrainingEventRequest? = openDraft(id, DraftKind.TRAINING_EVENT, TrainingEventRequest::class.java)
    override fun figureForForm(id: UUID): DanceFigureRequest? = openDraft(id, DraftKind.FIGURE, DanceFigureRequest::class.java)

    private fun <T> openDraft(id: UUID, kind: DraftKind, type: Class<T>): T? =
        store.findOpenOrNull(id, kind)?.let { codec.read(it.payload, type) }

    // ── the three saves ────────────────────────────────────────────────────

    private fun saveNote(draft: AssistantDraft): Saved {
        val request = codec.read(draft.payload, MaterialRequest::class.java)
        val note = transactions.execute {
            val created = materialService.create(request)
            request.figureIds.distinct().forEach { materialService.addFigure(created.id!!, FigureRequest(danceFigureId = it)) }
            val sessionId = request.trainingEventId
            if (request.markAttended && sessionId != null) {
                trainingEventService.updateAttendance(sessionId, AttendanceStatus.ATTENDED)
            }
            created
        }!!
        val notice = request.trainingEventId?.let { linkToSession(it, note.id!!) }
        return Saved(note.id!!, notice)
    }

    /** The same call the form makes. It writes to Google, so it runs after the database transaction. */
    private fun linkToSession(sessionId: UUID, materialId: UUID): String? = try {
        val session = trainingEventService.findById(sessionId)
        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = listOf(sessionId), materialId = materialId, materialsUrl = session.materialsUrl
        )
        if (result.updatedCount == 0) LINK_FAILED else null
    } catch (e: RuntimeException) {
        log.warn("Note {} was saved but could not be linked to session {}", materialId, sessionId, e)
        LINK_FAILED
    }

    private fun saveSession(draft: AssistantDraft): Saved {
        val request = codec.read(draft.payload, TrainingEventRequest::class.java)
        val target = activeCalendarService.validateCreationTarget(request.calendarId)
        val created = trainingEventService.create(request.copy(calendarId = target.id))
        return Saved(created.id!!, null)
    }

    private fun saveFigure(draft: AssistantDraft): Saved {
        val created = danceFigureService.create(codec.read(draft.payload, DanceFigureRequest::class.java))
        return Saved(created.id!!, null)
    }

    /** What a person may read of a failure. Anything unexpected is logged, and the card says something generic. */
    private fun problemText(e: RuntimeException): String = when (e) {
        is CalendarSyncException, is IllegalArgumentException, is EntityNotFoundException, is AccessDeniedException ->
            e.message?.takeIf { it.isNotBlank() } ?: GENERIC_FAILURE
        else -> {
            log.error("Saving a draft failed", e)
            GENERIC_FAILURE
        }
    }
}
