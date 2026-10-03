package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.DraftKind
import java.util.UUID

/**
 * What the rest of the assistant does with drafts. Every method is scoped to the current user:
 * someone else's draft is an `EntityNotFoundException` (a 404), never a 403.
 */
interface AssistantDraftService {

    /** Stores a validated draft for [conversationId] and returns its id. Writes nothing else. */
    fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID

    /** Ties the draft to the tool message that produced it. */
    fun attach(draftId: UUID, messageId: UUID)

    fun view(id: UUID): DraftView

    /** The cards for [ids], skipping any that no longer exist. */
    fun views(ids: Collection<UUID>): List<DraftView>

    /**
     * Saves the draft through the same service the form calls. Returns the card as it is afterwards:
     * `SAVED` with a link, or still `PENDING` with the error in `notice`. A draft that is not
     * pending throws [DraftNotPendingException] and nothing is written.
     */
    fun save(id: UUID): DraftView

    /** "Edit in form": marks the draft discarded and returns the create form's URL, prefilled from it. */
    fun openInForm(id: UUID): String

    /** The draft as the create form's request, or null when it is missing, foreign, already saved or of another kind. */
    fun noteForForm(id: UUID): MaterialRequest?
    fun sessionForForm(id: UUID): TrainingEventRequest?
    fun figureForForm(id: UUID): DanceFigureRequest?
}
