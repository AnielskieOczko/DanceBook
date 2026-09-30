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
@Table(name = "assistant_message")
class AssistantMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    var conversation: AssistantConversation? = null

    /** Order within the conversation. Unique per conversation, so two racing writers cannot interleave. */
    @Column(nullable = false)
    var position: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: AssistantRole = AssistantRole.USER

    @Column(nullable = false, columnDefinition = "text")
    var content: String = ""

    /** For a TOOL message: `name`, `arguments` and `result` of the call. A MutableMap so Hibernate sees `Map<String, Object>`. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_payload", columnDefinition = "jsonb")
    var toolPayload: MutableMap<String, Any?>? = null

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}
