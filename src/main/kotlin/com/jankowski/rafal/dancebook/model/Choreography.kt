package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "choreography")
class Choreography {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(nullable = false)
    var name: String = ""

    var description: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_type_id", nullable = false)
    var danceType: DanceType? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    var owner: AppUser? = null

    @Column(name = "is_public", nullable = false)
    var isPublic: Boolean = false

    @OneToMany(mappedBy = "choreography", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var entries: MutableList<ChoreographyEntry> = mutableListOf()

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
}
