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
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "share")
class Share {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(name = "item_type", nullable = false)
    var itemType: String = ""

    @Column(name = "item_id", nullable = false)
    var itemId: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grantee_user_id", nullable = false)
    var granteeUser: AppUser? = null

    @Column(nullable = false)
    var permission: String = "VIEW"

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}
