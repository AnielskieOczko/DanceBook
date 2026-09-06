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

    @Column(nullable = false)
    var title: String = ""

    @Column(name = "start_time", nullable = false)
    var startTime: LocalDateTime = LocalDateTime.now()

    @Column(name = "end_time", nullable = false)
    var endTime: LocalDateTime = LocalDateTime.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: TrainingEventType = TrainingEventType.TRAINING

    /** Null means the session is not style-specific (competitions, camps). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_category_id")
    var danceCategory: DanceCategory? = null

    @Column(columnDefinition = "TEXT")
    var description: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    var material: Material? = null

    /** For genuinely external links; prefer the [material] reference where one exists. */
    @Column(name = "materials_url", length = 1000)
    var materialsUrl: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", nullable = false)
    var attendanceStatus: AttendanceStatus = AttendanceStatus.PLANNED

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    var createdBy: AppUser? = null

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    /**
     * A past event still marked PLANNED was never confirmed either way. The status is
     * derived at read time rather than reconciled by a job, so nothing silently mutates
     * user records and the statistics in later phases read the same derivation.
     */
    val isAwaitingConfirmation: Boolean
        get() = attendanceStatus == AttendanceStatus.PLANNED && endTime.isBefore(LocalDateTime.now())

    /** Session length in minutes; the unit every phase-2 duration statistic sums. */
    val durationMinutes: Long
        get() = java.time.Duration.between(startTime, endTime).toMinutes()
}
