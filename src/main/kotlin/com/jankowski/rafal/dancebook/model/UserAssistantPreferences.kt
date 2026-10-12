package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "user_assistant_preferences")
class UserAssistantPreferences {

    @Id
    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "assistant_enabled", nullable = false)
    var assistantEnabled: Boolean = true

    @Column(name = "semantic_search_enabled", nullable = false)
    var semanticSearchEnabled: Boolean = true

    @Column(name = "related_notes_enabled", nullable = false)
    var relatedNotesEnabled: Boolean = true

    @Column(name = "suggest_figures_enabled", nullable = false)
    var suggestFiguresEnabled: Boolean = true

    @Column(name = "open_in_full_window", nullable = false)
    var openInFullWindow: Boolean = false

    @Enumerated(EnumType.STRING)
    @Column(name = "reply_language", nullable = false, length = 10)
    var replyLanguage: AssistantReplyLanguage = AssistantReplyLanguage.APP

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
}
