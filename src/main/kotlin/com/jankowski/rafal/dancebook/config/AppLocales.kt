package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.dto.FormSelectOption
import java.util.Locale

object AppLocales {
    val ENGLISH: Locale = Locale.ENGLISH
    val POLISH: Locale = Locale.forLanguageTag("pl")

    val DEFAULT: Locale = ENGLISH

    val ALL: List<Locale> = listOf(ENGLISH, POLISH)

    val SUPPORTED_CODES: Set<String> = ALL.map { it.language.lowercase() }.toSet()

    const val MAX_LOCALE_LENGTH = 10

    fun normalize(code: String?): String? {
        val trimmed = code?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty()) return null
        return when {
            trimmed == "pl" || trimmed.startsWith("pl-") || trimmed.startsWith("pl_") -> "pl"
            trimmed == "en" || trimmed.startsWith("en-") || trimmed.startsWith("en_") -> "en"
            else -> trimmed
        }
    }

    fun isSupported(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val trimmed = code.trim()
        if (trimmed.length > MAX_LOCALE_LENGTH) return false
        val normalized = normalize(trimmed)
        return SUPPORTED_CODES.contains(normalized)
    }

    fun parseLocale(code: String?): Locale? {
        val normalized = normalize(code) ?: return null
        return when (normalized) {
            "pl" -> POLISH
            "en" -> ENGLISH
            else -> null
        }
    }

    fun options(): List<FormSelectOption> = listOf(
        FormSelectOption("en", "English"),
        FormSelectOption("pl", "Polski")
    )
}
