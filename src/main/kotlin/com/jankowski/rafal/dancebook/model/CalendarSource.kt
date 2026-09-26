package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "calendar_source")
class CalendarSource {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id", nullable = false)
    var calendar: TrainingCalendar? = null

    @Column(name = "google_calendar_id", nullable = false)
    var googleCalendarId: String = ""

    @Column(name = "display_name")
    var displayName: String? = null

    @Column(name = "is_write_target", nullable = false)
    var isWriteTarget: Boolean = false

    @Column(name = "sync_token", columnDefinition = "TEXT")
    var syncToken: String? = null

    @Column(name = "last_synced_at")
    var lastSyncedAt: LocalDateTime? = null

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    @get:Transient
    val effectiveName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: googleCalendarId
}
