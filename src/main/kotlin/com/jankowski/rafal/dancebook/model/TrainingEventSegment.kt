package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.util.UUID

/**
 * A stretch of one training session spent on a single dance style, e.g. the first hour of
 * a three-hour session spent on Standard.
 *
 * Durations are plain minutes rather than a second timestamp pair, matching how [Figure]
 * stores a sub-range of a Material. The segments of an event may sum to less than its
 * wall-clock length — the remainder is untracked time such as a warm-up or a break — but
 * never to more.
 */
@Entity
@Table(name = "training_event_segment")
class TrainingEventSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_event_id", nullable = false)
    var trainingEvent: TrainingEvent? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_category_id", nullable = false)
    var danceCategory: DanceCategory? = null

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
