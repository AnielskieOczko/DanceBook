package com.jankowski.rafal.dancebook.model

enum class AssistantReplyLanguage {
    APP, PL, EN;

    companion object {
        fun fromStringOrNull(value: String?): AssistantReplyLanguage? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}
