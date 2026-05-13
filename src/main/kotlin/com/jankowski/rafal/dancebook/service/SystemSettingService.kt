package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.SystemSetting
import com.jankowski.rafal.dancebook.repository.SystemSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SystemSettingService(
    private val systemSettingRepository: SystemSettingRepository
) {

    @Transactional(readOnly = true)
    fun getSetting(key: String, defaultValue: String): String {
        return systemSettingRepository.findBySettingKey(key)?.settingValue ?: defaultValue
    }

    @Transactional(readOnly = true)
    fun getIntSetting(key: String, defaultValue: Int): Int {
        val value = getSetting(key, defaultValue.toString())
        return value.toIntOrNull() ?: defaultValue
    }

    @Transactional
    fun updateSetting(key: String, value: String) {
        var setting = systemSettingRepository.findBySettingKey(key)
        if (setting == null) {
            setting = SystemSetting().apply {
                this.settingKey = key
                this.settingValue = value
            }
        } else {
            setting.settingValue = value
        }
        systemSettingRepository.save(setting)
    }
}
