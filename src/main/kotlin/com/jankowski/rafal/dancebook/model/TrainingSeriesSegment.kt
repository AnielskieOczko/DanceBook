package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.util.UUID

/**
 * The style-mix template on a [TrainingSeries], copied onto each generated occurrence as a
 * [TrainingEventSegment].
 *
 * Deliberately a separate table rather than sharing one with a nullable double FK: it keeps
 * the series self-describing when occurrences are regenerated, and lets an occurrence's mix
 * diverge from the series without affecting the template.
 */
@Entity
@Table(name = "training_series_segment")
class TrainingSeriesSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_series_id", nullable = false)
    var trainingSeries: TrainingSeries? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_category_id", nullable = false)
    var danceCategory: DanceCategory? = null

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
