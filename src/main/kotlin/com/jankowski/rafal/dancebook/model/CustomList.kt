package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "custom_list")
class CustomList {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    var name: String = ""

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    var owner: AppUser? = null

    var nameFilter: String? = null

    var minRating: Short? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    var visibility: Visibility = Visibility.PRIVATE

    @get:Transient
    var isPublic: Boolean
        get() = visibility == Visibility.PUBLIC
        set(value) {
            visibility = if (value) Visibility.PUBLIC else Visibility.PRIVATE
        }

    @Column(name = "image_filename")
    var imageFilename: String? = null

    @ManyToMany
    @JoinTable(
        name = "custom_list_dance_type",
        joinColumns = [JoinColumn(name = "list_id")],
        inverseJoinColumns = [JoinColumn(name = "dance_type_id")]
    )
    var danceTypes: MutableSet<DanceType> = mutableSetOf()

    @ManyToMany
    @JoinTable(
        name = "custom_list_dance_category",
        joinColumns = [JoinColumn(name = "list_id")],
        inverseJoinColumns = [JoinColumn(name = "dance_category_id")]
    )
    var danceCategories: MutableSet<DanceCategory> = mutableSetOf()

    @Column(updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}
