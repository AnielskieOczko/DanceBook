package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "dance_figure_step")
class DanceFigureStep {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_figure_id", nullable = false)
    var danceFigure: DanceFigure? = null

    @Column(name = "step_number", nullable = false)
    var stepNumber: Int = 1

    @Column(nullable = false)
    var timing: String = ""

    @Column(nullable = false)
    var role: String = "" // "LEADER" or "FOLLOWER"

    @Column(nullable = false)
    var foot: String = "" // "LF", "RF", "TOGETHER", etc.

    @Column(nullable = false)
    var action: String = ""

    var footwork: String? = null
    var alignment: String? = null

    @Column(name = "amount_of_turn")
    var amountOfTurn: String? = null

    @OneToMany(mappedBy = "danceFigureStep", cascade = [CascadeType.ALL], orphanRemoval = true)
    var comments: MutableList<DanceFigureStepComment> = mutableListOf()
}

