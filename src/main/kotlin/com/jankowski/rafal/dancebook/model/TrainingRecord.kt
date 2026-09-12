package com.jankowski.rafal.dancebook.model

import jakarta.persistence.CascadeType
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
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * A permanent record of one confirmed training session.
 *
 * A [TrainingEvent] is a schedule entry and is meant to be disposable: you tidy a cluttered
 * calendar, you throw away a series you created by mistake. A training record is not. So this
 * row holds its session by plain id with no foreign key -- exactly as [ActivityEvent] holds
 * its target -- and keeps its own copies of the title, duration and style names. When the
 * session is deleted the record is stamped [orphanedAt] and stands on its own, unchanged for
 * good.
 *
 * One record per session, updated in place. Changing your mind from attended to skipped
 * rewrites this row rather than appending a correction, so statistics never have to window
 * down to the latest row per session.
 */
@Entity
@Table(name = "training_record")
class TrainingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    /**
     * The session this was recorded from. A plain id rather than a @ManyToOne: a foreign key
     * would either cascade the record away with its session or block the session's delete,
     * and the whole point of this table is that neither happens.
     */
    @Column(name = "training_event_id", nullable = false, unique = true, updatable = false)
    var trainingEventId: UUID? = null

    /** When the session happened. Snapshotted, so rescheduling a past session cannot move it. */
    @Column(name = "occurred_at", nullable = false)
    var occurredAt: LocalDateTime = LocalDateTime.now()

    /**
     * How long the session ran. Recorded rather than derived from a timestamp pair, so
     * rescheduling an already-attended session cannot quietly change historical hours.
     */
    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var outcome: TrainingOutcome = TrainingOutcome.ATTENDED

    /** Snapshotted so an orphaned record still reads as something recognisable. */
    @Column(nullable = false)
    var title: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: TrainingEventType = TrainingEventType.TRAINING

    @OneToMany(mappedBy = "trainingRecord", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var segments: MutableList<TrainingRecordSegment> = mutableListOf()

    /**
     * Restricts rather than cascades, following [ActivityEvent.actor]: a session's
     * `created_by_id` may cascade on user delete, but deleting a user must not silently take
     * their training history with it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false, updatable = false)
    var createdBy: AppUser? = null

    /**
     * Stamped when the session is deleted. From that moment the record is frozen: it is read
     * entirely from its own snapshots, and nothing will ever update it again.
     */
    @Column(name = "orphaned_at")
    var orphanedAt: LocalDateTime? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    /** Its calendar session is gone. The record stands alone, and cannot be corrected in place. */
    val isOrphaned: Boolean
        get() = orphanedAt != null
}
