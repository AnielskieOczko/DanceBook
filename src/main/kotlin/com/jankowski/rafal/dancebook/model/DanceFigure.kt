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
}
