package com.jankowski.rafal.dancebook.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "dance_figure_variation")
class DanceFigureVariation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_figure_id", nullable = false)
    var danceFigure: DanceFigure? = null

    @Column(nullable = false)
    var name: String = ""

    @Column(nullable = false)
    var timing: String = ""

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false

    @Column(name = "starting_foot_leader")
    var startingFootLeader: String? = null

    @Column(name = "ending_foot_leader")
    var endingFootLeader: String? = null

    @Column(name = "starting_foot_follower")
    var startingFootFollower: String? = null

    @Column(name = "ending_foot_follower")
    var endingFootFollower: String? = null

    @Column(name = "starting_position")
    var startingPosition: String? = null

    @Column(name = "ending_position")
    var endingPosition: String? = null

    @OneToMany(mappedBy = "danceFigureVariation", cascade = [CascadeType.ALL], orphanRemoval = true)
    var steps: MutableList<DanceFigureStep> = mutableListOf()

    fun getLeaderSteps(): List<DanceFigureStep> =
        steps.filter { it.role == "LEADER" }.sortedBy { it.stepNumber }

    fun getFollowerSteps(): List<DanceFigureStep> =
        steps.filter { it.role == "FOLLOWER" }.sortedBy { it.stepNumber }
}
