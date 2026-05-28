package com.jankowski.rafal.dancebook.model

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
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "dance_figure")
class DanceFigure {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(nullable = false)
    var name: String = ""

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_type_id", nullable = false)
    var danceType: DanceType? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "dance_class")
    var danceClass: DanceClass? = null

    @Column(nullable = false)
    var predefined: Boolean = false

    @Column(name = "alternative_timing")
    var alternativeTiming: String? = null

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

    @Column(name = "preceding_figure_names")
    var precedingFigureNames: String? = null

    @Column(name = "following_figure_names")
    var followingFigureNames: String? = null

    @jakarta.persistence.OneToMany(mappedBy = "danceFigure", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true)
    var steps: MutableList<DanceFigureStep> = mutableListOf()
}

