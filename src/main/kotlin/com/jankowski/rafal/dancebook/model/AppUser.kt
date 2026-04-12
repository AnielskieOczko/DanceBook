package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "app_user")
class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    var username: String = ""

    var email: String? = null

    var displayName: String = ""

    var password: String? = null

    @Enumerated(EnumType.STRING)
    var role: Role = Role.USER

    @Column(updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}