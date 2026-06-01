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

@Entity
@Table(name = "dance_figure_step_comment")
class DanceFigureStepComment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_figure_step_id", nullable = false)
    var danceFigureStep: DanceFigureStep? = null

    @Column(name = "comment_text", nullable = false, columnDefinition = "TEXT")
    var commentText: String = ""

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0
}
