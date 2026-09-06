package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.util.UUID

/**
 * The definition behind a repeating training session — "every Monday 18:00 until June".
 *
 * The series is a template, not a calendar entry. Creating one generates an independent
 * [TrainingEvent] per occurrence, each with its own Google event, so attendance stays
 * per-occurrence and everything built for single events keeps working unchanged.
 *
 * Users never navigate here: the series is created from the Repeats control on the event
 * form, and changed through the "this and following" option on any occurrence.
 */
@Entity
@Table(name = "training_series")
class TrainingSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(nullable = false)
    var title: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    var dayOfWeek: DayOfWeek = DayOfWeek.MONDAY

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime = LocalTime.of(18, 0)

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime = LocalTime.of(19, 0)

    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate = LocalDate.now()

    @Column(name = "ends_on", nullable = false)
    var endsOn: LocalDate = LocalDate.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: TrainingEventType = TrainingEventType.TRAINING

    @Column(columnDefinition = "TEXT")
    var description: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    var material: Material? = null

    @Column(name = "materials_url", length = 1000)
    var materialsUrl: String? = null

    /** The style mix every generated occurrence starts with. */
    @OneToMany(mappedBy = "trainingSeries", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var segments: MutableList<TrainingSeriesSegment> = mutableListOf()

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    var createdBy: AppUser? = null

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
}
