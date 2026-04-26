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
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "comment")
class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null
    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String? = null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    var author: AppUser? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", nullable = false)
    var material: Material? = null
    var createdAt: LocalDateTime = LocalDateTime.now()
    var updatedAt: LocalDateTime? = null

}