package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "dance_figure_step_set")
class DanceFigureStepSet {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(nullable = false)
    var name: String = ""

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_figure_id", nullable = false)
    var danceFigure: DanceFigure? = null

    @OneToMany(mappedBy = "danceFigureStepSet", cascade = [CascadeType.ALL], orphanRemoval = true)
    var steps: MutableList<DanceFigureStep> = mutableListOf()

    fun getLeaderSteps(): List<DanceFigureStep> =
        steps.filter { it.role == "LEADER" }.sortedBy { it.stepNumber }

    fun getFollowerSteps(): List<DanceFigureStep> =
        steps.filter { it.role == "FOLLOWER" }.sortedBy { it.stepNumber }
}
