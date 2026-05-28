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
@Table(name = "dance_figure_link")
class DanceFigureLink {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_figure_id", nullable = false)
    var danceFigure: DanceFigure? = null

    @Column(nullable = false, length = 512)
    var url: String = ""

    @Column(length = 255)
    var title: String? = null

    @Column(length = 50)
    var type: String? = null
}
