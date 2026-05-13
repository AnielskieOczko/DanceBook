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
import java.time.LocalDateTime
import java.util.UUID

enum class EventType {
    MATERIAL_CREATED,
    MATERIAL_UPDATED,
    MATERIAL_DELETED,
    COMMENT_ADDED,
    LIST_CREATED,
    LIST_MADE_PUBLIC
}

enum class TargetType {
    MATERIAL,
    COMMENT,
    CUSTOM_LIST
}

@Entity
@Table(name = "activity_event")
class ActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: EventType = EventType.MATERIAL_CREATED

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    var actor: AppUser? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    var targetType: TargetType = TargetType.MATERIAL

    @Column(name = "target_id")
    var targetId: UUID? = null

    @Column(name = "target_name")
    var targetName: String? = null

    @Column(name = "metadata", columnDefinition = "TEXT")
    var metadata: String? = null

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}
