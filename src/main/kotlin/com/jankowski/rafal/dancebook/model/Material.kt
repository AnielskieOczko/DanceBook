package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
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
    @JoinColumn(name = "owner_id", nullable = false)
    var owner: AppUser? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    var visibility: Visibility = Visibility.PRIVATE

    @get:Transient
    val isPublic: Boolean
        get() = visibility == Visibility.PUBLIC

    @ManyToOne
    @JoinColumn(name = "dance_type_id")
    var danceType: DanceType? = null
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