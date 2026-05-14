package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.SystemSetting
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SystemSettingRepository : JpaRepository<SystemSetting, UUID> {
    fun findBySettingKey(settingKey: String): SystemSetting?
}
