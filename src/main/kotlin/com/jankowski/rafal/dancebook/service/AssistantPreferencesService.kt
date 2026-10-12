package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.AssistantPreferences
import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.model.AppUser

interface AssistantPreferencesService {
    /**
     * Returns the effective assistant preferences for the given user, folding in the global API key check.
     * Anonymous visitors or read failures return the defaults.
     * With no API key configured, assistant-dependent features are forced off.
     */
    fun forUser(user: AppUser?): AssistantPreferences

    /**
     * Returns raw stored preferences (or defaults if absent) without folding in the global API key override.
     * Used by the profile page to render user choices.
     */
    fun rawForUser(user: AppUser): AssistantPreferences

    /**
     * Updates and persists the preferences for the given user.
     * Writes a row on first save.
     */
    fun updatePreferences(user: AppUser, update: AssistantPreferencesUpdateRequest): AssistantPreferences
}
