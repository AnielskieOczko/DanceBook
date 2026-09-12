package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

/**
 * One style's share of a recorded session — the snapshot counterpart of
 * [TrainingEventSegment].
 *
 * It keeps both a live link to the category and a copy of the category's name at the time.
 * The link is what makes a rename relabel the history that belongs to that style instead of
 * splitting it in two; the copy is what is left to read once the category is deleted.
 */
@Entity
@Table(name = "training_record_segment")
class TrainingRecordSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_record_id", nullable = false)
    var trainingRecord: TrainingRecord? = null

    /**
     * Nullable on purpose, and ON DELETE SET NULL in the schema: a category may be deleted
     * long after the session happened, and that must cost the link rather than the record.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_category_id")
    var danceCategory: DanceCategory? = null

    @Column(name = "category_name", nullable = false)
    var categoryName: String = ""

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    /** The live category's name while it exists, the snapshot once it does not. */
    val label: String
        get() = danceCategory?.name ?: categoryName
}
