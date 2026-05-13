package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "system_setting")
class SystemSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @Column(name = "setting_key", nullable = false, unique = true)
    var settingKey: String = ""

    @Column(name = "setting_value", nullable = false, length = 1024)
    var settingValue: String = ""
}
