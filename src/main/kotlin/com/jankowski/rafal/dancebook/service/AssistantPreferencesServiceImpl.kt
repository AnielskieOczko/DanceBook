package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.dto.AssistantPreferences
import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantReplyLanguage
import com.jankowski.rafal.dancebook.model.UserAssistantPreferences
import com.jankowski.rafal.dancebook.repository.UserAssistantPreferencesRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AssistantPreferencesServiceImpl(
    private val repository: UserAssistantPreferencesRepository,
    private val assistantFeature: AssistantFeature
) : AssistantPreferencesService {

    companion object {
        private val log = LoggerFactory.getLogger(AssistantPreferencesServiceImpl::class.java)
    }

    override fun forUser(user: AppUser?): AssistantPreferences {
        val raw = if (user?.id == null) {
            AssistantPreferences.DEFAULTS
        } else {
            try {
                repository.findById(user.id!!).map { toDto(it) }.orElse(AssistantPreferences.DEFAULTS)
            } catch (e: Exception) {
                log.warn("Failed to read assistant preferences for user {}, falling back to defaults: {}", user.id, e.message)
                AssistantPreferences.DEFAULTS
            }
        }

        return if (!assistantFeature.enabled) {
            raw.copy(
                assistantEnabled = false,
                semanticSearchEnabled = false,
                relatedNotesEnabled = false,
                openInFullWindow = false
            )
        } else {
            raw
        }
    }

    override fun rawForUser(user: AppUser): AssistantPreferences {
        if (user.id == null) return AssistantPreferences.DEFAULTS
        return try {
            repository.findById(user.id!!).map { toDto(it) }.orElse(AssistantPreferences.DEFAULTS)
        } catch (e: Exception) {
            log.warn("Failed to read raw assistant preferences for user {}: {}", user.id, e.message)
            AssistantPreferences.DEFAULTS
        }
    }

    @Transactional
    override fun updatePreferences(user: AppUser, update: AssistantPreferencesUpdateRequest): AssistantPreferences {
        val userId = user.id ?: throw IllegalArgumentException("User must have an ID to update preferences")
        val entity = repository.findById(userId).orElseGet {
            UserAssistantPreferences().apply { this.userId = userId }
        }

        update.assistantEnabled?.let { entity.assistantEnabled = it }
        update.semanticSearchEnabled?.let { entity.semanticSearchEnabled = it }
        update.relatedNotesEnabled?.let { entity.relatedNotesEnabled = it }
        update.suggestFiguresEnabled?.let { entity.suggestFiguresEnabled = it }
        update.openInFullWindow?.let { entity.openInFullWindow = it }
        update.replyLanguage?.let { entity.replyLanguage = it }
        update.includeSharedItems?.let { entity.includeSharedItems = it }
        update.showStreaksAndBadges?.let { entity.showStreaksAndBadges = it }
        update.rememberChatHistory?.let { entity.rememberChatHistory = it }
        entity.updatedAt = LocalDateTime.now()

        val saved = repository.save(entity)
        return toDto(saved)
    }

    private fun toDto(entity: UserAssistantPreferences): AssistantPreferences =
        AssistantPreferences(
            assistantEnabled = entity.assistantEnabled,
            semanticSearchEnabled = entity.semanticSearchEnabled,
            relatedNotesEnabled = entity.relatedNotesEnabled,
            suggestFiguresEnabled = entity.suggestFiguresEnabled,
            openInFullWindow = entity.openInFullWindow,
            replyLanguage = entity.replyLanguage,
            includeSharedItems = entity.includeSharedItems,
            showStreaksAndBadges = entity.showStreaksAndBadges,
            rememberChatHistory = entity.rememberChatHistory
        )
}
