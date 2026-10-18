package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSessionEvent
import jakarta.servlet.http.HttpSessionListener
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
@Component
class AssistantConversationCleanup(
    private val conversationRepository: AssistantConversationRepository,
    private val appUserRepository: AppUserRepository
) : LogoutHandler, HttpSessionListener {

    companion object {
        private val log = LoggerFactory.getLogger(AssistantConversationCleanup::class.java)
    }

    @Transactional
    override fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        try {
            val sessionId = request.getSession(false)?.id
            if (sessionId != null) {
                conversationRepository.deleteByEphemeralTrueAndSessionId(sessionId)
            }
            val username = authentication?.name
            if (username != null) {
                val user = appUserRepository.findByUsername(username) ?: appUserRepository.findByEmailIgnoreCase(username)
                if (user?.id != null) {
                    conversationRepository.deleteByEphemeralTrueAndOwnerId(user.id!!)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to prune ephemeral conversations on logout: {}", e.message)
        }
    }

    @Transactional
    override fun sessionDestroyed(se: HttpSessionEvent) {
        try {
            conversationRepository.deleteByEphemeralTrueAndSessionId(se.session.id)
        } catch (e: Exception) {
            log.warn("Failed to prune ephemeral conversations on session expiration: {}", e.message)
        }
    }

    @Transactional
    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        try {
            conversationRepository.deleteByEphemeralTrue()
        } catch (e: Exception) {
            log.warn("Failed to prune ephemeral conversations on startup: {}", e.message)
        }
    }
}
