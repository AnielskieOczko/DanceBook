package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.dto.AssistantPreferences
import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantReplyLanguage
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.UserAssistantPreferences
import com.jankowski.rafal.dancebook.repository.UserAssistantPreferencesRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class AssistantPreferencesServiceTest {

    @Mock
    private lateinit var repository: UserAssistantPreferencesRepository

    private lateinit var featureEnabled: AssistantFeature
    private lateinit var featureDisabled: AssistantFeature

    private lateinit var serviceWithKey: AssistantPreferencesServiceImpl
    private lateinit var serviceWithoutKey: AssistantPreferencesServiceImpl

    private val user = AppUser().apply {
        id = UUID.randomUUID()
        username = "alice"
        role = Role.USER
    }

    @BeforeEach
    fun setUp() {
        featureEnabled = AssistantFeature(GoogleAiProperties(apiKey = "valid-key"))
        featureDisabled = AssistantFeature(GoogleAiProperties(apiKey = ""))
        serviceWithKey = AssistantPreferencesServiceImpl(repository, featureEnabled)
        serviceWithoutKey = AssistantPreferencesServiceImpl(repository, featureDisabled)
    }

    @Test
    fun `no row gives defaults and creates nothing`() {
        `when`(repository.findById(user.id!!)).thenReturn(Optional.empty())

        val prefs = serviceWithKey.forUser(user)

        assertEquals(AssistantPreferences.DEFAULTS, prefs)
        assertTrue(prefs.assistantEnabled)
        assertTrue(prefs.semanticSearchEnabled)
        assertTrue(prefs.relatedNotesEnabled)
        assertTrue(prefs.suggestFiguresEnabled)
        assertFalse(prefs.openInFullWindow)
        assertEquals(AssistantReplyLanguage.APP, prefs.replyLanguage)
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `saved row is read back when key is enabled`() {
        val entity = UserAssistantPreferences().apply {
            userId = user.id
            assistantEnabled = true
            semanticSearchEnabled = false
            relatedNotesEnabled = false
            suggestFiguresEnabled = true
            openInFullWindow = true
            replyLanguage = AssistantReplyLanguage.PL
        }
        `when`(repository.findById(user.id!!)).thenReturn(Optional.of(entity))

        val prefs = serviceWithKey.forUser(user)

        assertTrue(prefs.assistantEnabled)
        assertFalse(prefs.semanticSearchEnabled)
        assertFalse(prefs.relatedNotesEnabled)
        assertTrue(prefs.suggestFiguresEnabled)
        assertTrue(prefs.openInFullWindow)
        assertEquals(AssistantReplyLanguage.PL, prefs.replyLanguage)
    }

    @Test
    fun `missing API key forces assistant dependent flags off even if saved on`() {
        val entity = UserAssistantPreferences().apply {
            userId = user.id
            assistantEnabled = true
            semanticSearchEnabled = true
            relatedNotesEnabled = true
            suggestFiguresEnabled = true
            openInFullWindow = true
            replyLanguage = AssistantReplyLanguage.EN
        }
        `when`(repository.findById(user.id!!)).thenReturn(Optional.of(entity))

        val prefs = serviceWithoutKey.forUser(user)

        assertFalse(prefs.assistantEnabled)
        assertFalse(prefs.semanticSearchEnabled)
        assertFalse(prefs.relatedNotesEnabled)
        assertTrue(prefs.suggestFiguresEnabled)
        assertFalse(prefs.openInFullWindow)
        assertEquals(AssistantReplyLanguage.EN, prefs.replyLanguage)
    }

    @Test
    fun `repository exception gives defaults rather than turning off or throwing`() {
        `when`(repository.findById(user.id!!)).thenThrow(RuntimeException("DB error"))

        val prefs = serviceWithKey.forUser(user)

        assertEquals(AssistantPreferences.DEFAULTS, prefs)
        assertTrue(prefs.assistantEnabled)
    }

    @Test
    fun `anonymous user gives defaults without calling repository`() {
        val prefs = serviceWithKey.forUser(null)

        assertEquals(AssistantPreferences.DEFAULTS, prefs)
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `anonymous user with missing key gives disabled defaults`() {
        val prefs = serviceWithoutKey.forUser(null)

        assertFalse(prefs.assistantEnabled)
        assertFalse(prefs.semanticSearchEnabled)
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `rawForUser returns stored values even when API key is missing`() {
        val entity = UserAssistantPreferences().apply {
            userId = user.id
            assistantEnabled = true
            semanticSearchEnabled = true
            relatedNotesEnabled = true
            suggestFiguresEnabled = true
            openInFullWindow = true
            replyLanguage = AssistantReplyLanguage.PL
        }
        `when`(repository.findById(user.id!!)).thenReturn(Optional.of(entity))

        val raw = serviceWithoutKey.rawForUser(user)

        assertTrue(raw.assistantEnabled)
        assertTrue(raw.openInFullWindow)
        assertEquals(AssistantReplyLanguage.PL, raw.replyLanguage)
    }

    @Test
    fun `updatePreferences creates new entity on first save and persists update`() {
        `when`(repository.findById(user.id!!)).thenReturn(Optional.empty())
        val captor = ArgumentCaptor.forClass(UserAssistantPreferences::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { it.arguments[0] }

        val result = serviceWithKey.updatePreferences(
            user,
            AssistantPreferencesUpdateRequest(
                assistantEnabled = false,
                openInFullWindow = true,
                replyLanguage = AssistantReplyLanguage.PL
            )
        )

        assertFalse(result.assistantEnabled)
        assertTrue(result.openInFullWindow)
        assertEquals(AssistantReplyLanguage.PL, result.replyLanguage)
        assertTrue(result.semanticSearchEnabled) // unchanged default
        val saved = captor.value
        assertEquals(user.id, saved.userId)
        assertFalse(saved.assistantEnabled)
        assertTrue(saved.openInFullWindow)
        assertEquals(AssistantReplyLanguage.PL, saved.replyLanguage)
    }
}
