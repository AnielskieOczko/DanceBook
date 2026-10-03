package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The drafts table and nothing else: it moves a draft through its states and never touches a
 * domain service. Every read is scoped to the current user through the draft's conversation, so
 * someone else's draft is the same [EntityNotFoundException] as a missing one.
 *
 * Methods return the entity for its scalar fields (`kind`, `status`, `payload`, `savedEntityId`,
 * `notice`). The `conversation` and `message` associations are lazy and closed by then.
 */
@Service
@ConditionalOnAssistant
class AssistantDraftStore(
    private val drafts: AssistantDraftRepository,
    private val messages: AssistantMessageRepository,
    private val conversations: AssistantConversationService,
    private val appUserService: AppUserService
) {

    @Transactional
    fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): AssistantDraft {
        val conversation = conversations.findOwned(conversationId)
        return drafts.save(AssistantDraft().apply {
            this.conversation = conversation
            this.kind = kind
            this.payload = payload
        })
    }

    /** Ties the draft to the tool message that produced it. A message from another conversation is ignored. */
    @Transactional
    fun attach(draftId: UUID, messageId: UUID) {
        val draft = owned(draftId)
        val message = messages.findById(messageId).orElse(null)
        if (message != null && message.conversation?.id == draft.conversation?.id) {
            draft.message = message
            drafts.save(draft)
        }
    }

    @Transactional(readOnly = true)
    fun findOwned(id: UUID): AssistantDraft = owned(id)

    /** The draft for a create form to prefill from: owned, of [kind], and not already saved. Otherwise null. */
    @Transactional(readOnly = true)
    fun findOpenOrNull(id: UUID, kind: DraftKind): AssistantDraft? =
        drafts.findOwned(id, currentUserId())?.takeIf { it.kind == kind && it.status != DraftStatus.SAVED }

    /** Step one of Save: under a row lock, `PENDING` becomes `SAVED` and commits, so a second Save is refused. */
    @Transactional
    fun claim(id: UUID): AssistantDraft {
        val draft = lockedOwned(id)
        if (draft.status != DraftStatus.PENDING) throw DraftNotPendingException(draft.status)
        draft.status = DraftStatus.SAVED
        draft.notice = null
        return drafts.save(draft)
    }

    /** The last step of a good Save: what was created, and a caveat to show if there is one. */
    @Transactional
    fun finish(id: UUID, entityId: UUID, notice: String?) {
        val draft = owned(id)
        draft.savedEntityId = entityId
        draft.notice = notice
        drafts.save(draft)
    }

    /** A Save that failed: back to `PENDING` with the error. A draft that finished in the meantime stays saved. */
    @Transactional
    fun release(id: UUID, notice: String) {
        val draft = lockedOwned(id)
        if (draft.status == DraftStatus.SAVED && draft.savedEntityId == null) {
            draft.status = DraftStatus.PENDING
            draft.notice = notice
            drafts.save(draft)
        }
    }

    /** "Edit in form": the draft is greyed out from here on, so it cannot also be saved from the card. */
    @Transactional
    fun discardForForm(id: UUID): AssistantDraft {
        val draft = lockedOwned(id)
        if (draft.status == DraftStatus.SAVED) throw DraftNotPendingException(draft.status)
        draft.status = DraftStatus.DISCARDED
        return drafts.save(draft)
    }

    private fun currentUserId(): UUID = appUserService.getCurrentUser().id!!

    private fun owned(id: UUID): AssistantDraft =
        drafts.findOwned(id, currentUserId()) ?: throw EntityNotFoundException("Draft not found")

    private fun lockedOwned(id: UUID): AssistantDraft =
        drafts.findOwnedForUpdate(id, currentUserId()) ?: throw EntityNotFoundException("Draft not found")
}
