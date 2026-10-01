package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "assistant_draft")
class AssistantDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    var conversation: AssistantConversation? = null

    /** The tool message that produced this draft. Null until that message has been stored. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    var message: AssistantMessage? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var kind: DraftKind = DraftKind.NOTE

    /** The validated request DTO for [kind], as JSON. A MutableMap so Hibernate sees `Map<String, Object>`. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var payload: MutableMap<String, Any?> = mutableMapOf()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DraftStatus = DraftStatus.PENDING

    @Column(name = "saved_entity_id")
    var savedEntityId: UUID? = null

    @Column(columnDefinition = "text")
    var notice: String? = null

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}
