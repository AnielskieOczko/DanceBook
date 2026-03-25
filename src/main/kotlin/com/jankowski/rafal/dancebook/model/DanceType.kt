package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "dance_type")
class DanceType {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null
    var name: String = ""
    var predefined: Boolean = false
}