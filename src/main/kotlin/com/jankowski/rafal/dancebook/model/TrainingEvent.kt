package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "training_event")
class TrainingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(name = "google_event_id", unique = true)
    var googleEventId: String? = null

    @Column(name = "ical_uid")
    var icalUid: String? = null

    @OneToMany(mappedBy = "trainingEvent", cascade = [CascadeType.ALL], orphanRemoval = true)
    var eventSources: MutableList<TrainingEventSource> = mutableListOf()

    @Column(nullable = false)
    var title: String = ""

    @Column(name = "start_time", nullable = false)
    var startTime: LocalDateTime = LocalDateTime.now()

    @Column(name = "end_time", nullable = false)
    var endTime: LocalDateTime = LocalDateTime.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: TrainingEventType = TrainingEventType.TRAINING

    /**
     * How the session splits across dance styles. An empty list means the session is not
     * style-specific (competitions, camps).
     */
    @OneToMany(mappedBy = "trainingEvent", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var segments: MutableList<TrainingEventSegment> = mutableListOf()

    @Column(columnDefinition = "TEXT")
    var description: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    var material: Material? = null

    /** For genuinely external links; prefer the [material] reference where one exists. */
    @Column(name = "materials_url", length = 1000)
    var materialsUrl: String? = null

    @OneToMany(mappedBy = "trainingEvent", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("createdAt ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    var attendances: MutableSet<Attendance> = mutableSetOf()

    /**
     * The repeating definition this occurrence came from, or null for a one-off — and also
     * null once an occurrence has been edited on its own via "this event", which detaches
     * it from the series.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id")
    var series: TrainingSeries? = null

    /**
     * The calendar this session belongs to, or null if the row predates the backfill;
     * the first write adopts the default and records it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id")
    var calendar: TrainingCalendar? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    var createdBy: AppUser? = null

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    /**
     * Attendance status for the given user, or [AttendanceStatus.PLANNED] when no attendance record exists.
     */
    fun attendanceFor(user: AppUser): AttendanceStatus =
        attendances.firstOrNull { it.user?.id == user.id }?.status ?: AttendanceStatus.PLANNED

    /**
     * A past event still marked PLANNED for [user] was never confirmed either way. The status is
     * derived at read time rather than reconciled by a job, so nothing silently mutates
     * user records and the statistics in later phases read the same derivation.
     *
     * Mirrored in SQL by TrainingEventSpecification's `awaitingConfirmation` filter, which
     * needs its own copy of this rule to run as a database predicate. A change here needs
     * the same change there.
     */
    @JvmOverloads
    fun isAwaitingConfirmationFor(user: AppUser, now: LocalDateTime = LocalDateTime.now()): Boolean =
        attendanceFor(user) == AttendanceStatus.PLANNED && endTime.isBefore(now)

    /**
     * Sets or updates attendance status for a specific user.
     */
    fun setAttendance(user: AppUser, status: AttendanceStatus) {
        val existing = attendances.firstOrNull { it.user?.id == user.id }
        if (existing != null) {
            existing.status = status
            existing.updatedAt = LocalDateTime.now()
        } else {
            val attendance = Attendance().apply {
                this.trainingEvent = this@TrainingEvent
                this.user = user
                this.status = status
                this.createdAt = LocalDateTime.now()
                this.updatedAt = LocalDateTime.now()
            }
            attendances.add(attendance)
        }
    }

    /**
     * Wall-clock session length in minutes. This is "total time trained"; per-style time
     * comes from [segments], which may sum to less when part of the session was a break.
     */
    val durationMinutes: Long
        get() = java.time.Duration.between(startTime, endTime).toMinutes()

    /** Distinct styles in segment order, for rendering badges. */
    val danceCategories: List<DanceCategory>
        get() = segments.mapNotNull { it.danceCategory }.distinctBy { it.id }
}
