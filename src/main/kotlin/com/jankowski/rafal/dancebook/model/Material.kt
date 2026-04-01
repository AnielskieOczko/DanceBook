package com.jankowski.rafal.dancebook.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "material")
class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null
    var name: String = ""
    var description: String? = null
    @ManyToOne
    @JoinColumn(name = "dance_type_id")
    var danceType: DanceType? = null
    @ManyToOne
    @JoinColumn(name = "dance_category_id")
    var danceCategory: DanceCategory? = null
    var rating: Short? = null
    var videoLink: String? = null
    var sourceLink: String? = null
    var driveFileId: String? = null

    @OneToMany(mappedBy = "material", cascade = [(CascadeType.ALL)], orphanRemoval = true)
    var figures: MutableList<Figure> = mutableListOf()

    @Version
    var version: Long = 0

    @Column(updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    var updatedAt: LocalDateTime? = null

}