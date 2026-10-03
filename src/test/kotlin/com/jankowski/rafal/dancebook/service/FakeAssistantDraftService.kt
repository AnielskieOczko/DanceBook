package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import java.util.UUID

/** Records what the tools draft, so a test can read it back. Only what the tools and the loop call is implemented. */
class FakeAssistantDraftService : AssistantDraftService {

    data class Created(val id: UUID, val kind: DraftKind, val payload: MutableMap<String, Any?>)

    val created = mutableListOf<Created>()
    val attached = mutableMapOf<UUID, UUID>()

    override fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID {
        val id = UUID.randomUUID()
        created += Created(id, kind, payload)
        return id
    }

    override fun attach(draftId: UUID, messageId: UUID) { attached[draftId] = messageId }

    override fun view(id: UUID): DraftView {
        val draft = created.first { it.id == id }
        val heading = (draft.payload["name"] ?: draft.payload["title"] ?: "Draft").toString()
        return DraftView(id, draft.kind, DraftStatus.PENDING, heading, emptyList(), emptyList(), null, null)
    }

    override fun views(ids: Collection<UUID>): List<DraftView> = ids.filter { id -> created.any { it.id == id } }.map { view(it) }

    override fun save(id: UUID): DraftView = error("not used by these tests")
    override fun openInForm(id: UUID): String = error("not used by these tests")
    override fun noteForForm(id: UUID): MaterialRequest? = null
    override fun sessionForForm(id: UUID): TrainingEventRequest? = null
    override fun figureForForm(id: UUID): DanceFigureRequest? = null
}
