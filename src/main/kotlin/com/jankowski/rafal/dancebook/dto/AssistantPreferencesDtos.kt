package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.AssistantReplyLanguage

/**
 * Immutable per-user preferences for assistant and semantic features.
 * When retrieved via `AssistantPreferencesService.forUser()`, the global
 * API key check is already folded in, disabling assistant-dependent features
 * if no key is configured.
 */
data class AssistantPreferences(
    val assistantEnabled: Boolean = true,
    val semanticSearchEnabled: Boolean = true,
    val relatedNotesEnabled: Boolean = true,
    val suggestFiguresEnabled: Boolean = true,
    val openInFullWindow: Boolean = false,
    val replyLanguage: AssistantReplyLanguage = AssistantReplyLanguage.APP
) {
    companion object {
        val DEFAULTS = AssistantPreferences()
    }
}

/**
 * Request payload for updating assistant preferences.
 */
data class AssistantPreferencesUpdateRequest(
    val assistantEnabled: Boolean? = null,
    val semanticSearchEnabled: Boolean? = null,
    val relatedNotesEnabled: Boolean? = null,
    val suggestFiguresEnabled: Boolean? = null,
    val openInFullWindow: Boolean? = null,
    val replyLanguage: AssistantReplyLanguage? = null
)
