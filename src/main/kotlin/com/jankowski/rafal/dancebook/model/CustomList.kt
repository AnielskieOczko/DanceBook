package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
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

    @Column(name = "is_public")
    var isPublic: Boolean = false

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
