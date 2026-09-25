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
import jakarta.persistence.Version
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

    /** Syllabus figures are imported with this set; only an admin may delete them. */
    @Column(nullable = false)
    var predefined: Boolean = false

    /** Null for syllabus imports, and for figures whose creator could not be recovered. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    var createdBy: AppUser? = null

    @Version
    var version: Long = 0

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

    @Column(name = "preceding_figure_names", columnDefinition = "TEXT")
    @jakarta.persistence.Convert(converter = StringListConverter::class)
    var precedingFigureNames: List<String> = emptyList()

    @Column(name = "following_figure_names", columnDefinition = "TEXT")
    @jakarta.persistence.Convert(converter = StringListConverter::class)
    var followingFigureNames: List<String> = emptyList()

    @jakarta.persistence.OneToMany(mappedBy = "danceFigure", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true)
    var stepSets: MutableList<DanceFigureStepSet> = mutableListOf()

    @jakarta.persistence.OneToMany(mappedBy = "danceFigure", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true)
    var links: MutableList<DanceFigureLink> = mutableListOf()

    @Column(columnDefinition = "TEXT")
    var notes: String? = null

    val steps: List<DanceFigureStep>
        get() = stepSets.find { it.isDefault }?.steps ?: stepSets.firstOrNull()?.steps ?: emptyList()

    /**
     * Figures are edited by everyone, but only the creator or an admin may delete one, and a
     * syllabus figure only an admin. Whether another user's item still uses the figure is
     * checked separately, by the service.
     */
    fun isDeletableBy(user: AppUser?): Boolean {
        if (user == null) return false
        if (user.role == Role.ADMIN) return true
        return !predefined && createdBy?.id != null && createdBy?.id == user.id
    }

    fun getLeaderSteps(): List<DanceFigureStep> =
        steps.filter { it.role == "LEADER" }.sortedBy { it.stepNumber }

    fun getFollowerSteps(): List<DanceFigureStep> =
        steps.filter { it.role == "FOLLOWER" }.sortedBy { it.stepNumber }
}


