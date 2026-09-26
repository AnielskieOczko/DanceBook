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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    var owner: AppUser? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    var visibility: Visibility = Visibility.PRIVATE

    @Column(name = "color")
    var color: String? = null

    @Column(name = "display_name", nullable = false)
    var displayName: String = ""

    @OneToMany(mappedBy = "calendar", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("createdAt ASC")
    var sources: MutableList<CalendarSource> = mutableListOf()

    @Column(name = "last_synced_at")
    var lastSyncedAt: LocalDateTime? = null

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    @get:Transient
    val isPublic: Boolean
        get() = visibility == Visibility.PUBLIC

    @get:Transient
    val writeTarget: CalendarSource?
        get() = sources.firstOrNull { it.isWriteTarget }

    fun requireWriteTarget(): CalendarSource =
        writeTarget ?: throw IllegalStateException("Training calendar '$displayName' has no write target.")

    fun addSource(googleCalendarId: String, displayName: String? = null, isWriteTarget: Boolean = false): CalendarSource {
        val source = CalendarSource().apply {
            this.calendar = this@TrainingCalendar
            this.googleCalendarId = googleCalendarId
            this.displayName = displayName
            this.isWriteTarget = isWriteTarget
        }
        sources.add(source)
        return source
    }

    fun isDefaultFor(user: AppUser?): Boolean {
        if (user == null) return false
        return user.defaultCalendar?.id == id
    }
}
