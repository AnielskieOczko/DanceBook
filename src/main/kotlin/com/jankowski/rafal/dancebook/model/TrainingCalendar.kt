package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "training_calendar")
class TrainingCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(name = "google_calendar_id", nullable = false, unique = true)
    var googleCalendarId: String = ""

    @Column(name = "display_name", nullable = false)
    var displayName: String = ""

    @Column(name = "sync_token", columnDefinition = "TEXT")
    var syncToken: String? = null

    @Column(name = "last_synced_at")
    var lastSyncedAt: LocalDateTime? = null

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
}
