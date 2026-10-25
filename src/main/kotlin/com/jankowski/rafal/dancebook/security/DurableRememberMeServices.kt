package com.jankowski.rafal.dancebook.security

import com.jankowski.rafal.dancebook.repository.AppUserRepository
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.RememberMeAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.RememberMeServices
import org.springframework.security.web.authentication.logout.LogoutHandler
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Service
class DurableRememberMeServices(
    private val persistentLoginRepository: PersistentLoginRepository,
    private val customUserDetailsService: CustomUserDetailsService,
    private val appUserRepository: AppUserRepository,
    @Value("\${dancebook.security.remember-me-key:dancebook-remember-me-secret-key}")
    val key: String
) : RememberMeServices, LogoutHandler {

    companion object {
        const val COOKIE_NAME = "remember-me"
        val REMEMBER_ME_DURATION: Duration = Duration.ofDays(28) // 4 weeks
        val UNTICKED_IDLE_TIMEOUT: Duration = Duration.ofMinutes(30) // 30 minutes
        val GRACE_PERIOD: Duration = Duration.ofSeconds(30)
        private val log = LoggerFactory.getLogger(DurableRememberMeServices::class.java)
        private val secureRandom = SecureRandom()
    }

    override fun autoLogin(request: HttpServletRequest, response: HttpServletResponse): Authentication? {
        val cookie = extractRememberMeCookie(request) ?: return null
        val (series, rawToken) = decodeCookie(cookie.value) ?: run {
            cancelCookie(request, response)
            return null
        }

        val record = persistentLoginRepository.findBySeries(series) ?: run {
            cancelCookie(request, response)
            return null
        }

        val now = Instant.now()
        val presentedHash = hashToken(rawToken)

        // Check if presented token matches current token
        val currentMatch = MessageDigest.isEqual(
            presentedHash.toByteArray(StandardCharsets.UTF_8),
            record.token.toByteArray(StandardCharsets.UTF_8)
        )

        // Check if presented token matches immediately previous token (within 30s grace period)
        val previousMatch = record.previousToken != null && MessageDigest.isEqual(
            presentedHash.toByteArray(StandardCharsets.UTF_8),
            record.previousToken.toByteArray(StandardCharsets.UTF_8)
        )

        if (previousMatch) {
            if (now.isBefore(record.lastUsed.plus(GRACE_PERIOD))) {
                // Concurrent request / second tab presenting old token within grace period
                log.debug("Accepted remember-me token within grace period for series '{}'", series)
                val userDetails = try {
                    customUserDetailsService.loadUserByUsername(record.username)
                } catch (e: Exception) {
                    persistentLoginRepository.deleteBySeries(series)
                    cancelCookie(request, response)
                    return null
                }
                return userDetails?.let { RememberMeAuthenticationToken(key, it, it.authorities) }
            } else {
                // Grace period expired for previous token
                log.warn("Remember-me previous token grace period expired for series '{}'", series)
                persistentLoginRepository.deleteBySeries(series)
                cancelCookie(request, response)
                return null
            }
        }

        if (!currentMatch) {
            // Genuine mismatch: delete only this series, leaving other user series intact
            log.warn("Remember-me token mismatch detected for series '{}'", series)
            persistentLoginRepository.deleteBySeries(series)
            cancelCookie(request, response)
            return null
        }

        // Expiry check
        if (now.isAfter(record.expiresAt)) {
            log.debug("Remember-me token for series '{}' has expired", series)
            persistentLoginRepository.deleteBySeries(series)
            cancelCookie(request, response)
            return null
        }

        // Token rotation on every auto-login: store old token as previousToken
        val newRawToken = generateRandomString(16)
        val newExpiresAt = if (record.rememberMe) {
            now.plus(REMEMBER_ME_DURATION)
        } else {
            now.plus(UNTICKED_IDLE_TIMEOUT)
        }
        persistentLoginRepository.update(series, hashToken(newRawToken), record.token, now, newExpiresAt)

        val maxAge = if (record.rememberMe) REMEMBER_ME_DURATION.seconds.toInt() else -1
        setRememberMeCookie(request, response, series, newRawToken, maxAge)

        val userDetails = try {
            customUserDetailsService.loadUserByUsername(record.username)
        } catch (e: Exception) {
            log.warn("User '{}' from remember-me token could not be loaded", record.username, e)
            persistentLoginRepository.deleteBySeries(series)
            cancelCookie(request, response)
            return null
        }

        return userDetails?.let { RememberMeAuthenticationToken(key, it, it.authorities) }
    }

    override fun loginSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        successfulAuthentication: Authentication
    ) {
        val username = resolveUsername(successfulAuthentication) ?: return
        val isRememberMe = isRememberMeRequested(request, successfulAuthentication)

        val now = Instant.now()
        // Purge expired tokens for this user upon sign-in
        persistentLoginRepository.deleteExpiredTokensForUser(username, now)

        val series = generateRandomString(16)
        val rawToken = generateRandomString(16)
        val expiresAt = if (isRememberMe) {
            now.plus(REMEMBER_ME_DURATION)
        } else {
            now.plus(UNTICKED_IDLE_TIMEOUT)
        }

        persistentLoginRepository.save(
            PersistentLoginToken(
                series = series,
                username = username,
                token = hashToken(rawToken),
                previousToken = null,
                lastUsed = now,
                rememberMe = isRememberMe,
                expiresAt = expiresAt
            )
        )

        val maxAge = if (isRememberMe) REMEMBER_ME_DURATION.seconds.toInt() else -1
        setRememberMeCookie(request, response, series, rawToken, maxAge)
    }

    override fun loginFail(request: HttpServletRequest, response: HttpServletResponse) {
        cancelCookie(request, response)
    }

    override fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication?
    ) {
        val cookie = extractRememberMeCookie(request)
        if (cookie != null) {
            val decoded = decodeCookie(cookie.value)
            if (decoded != null) {
                persistentLoginRepository.deleteBySeries(decoded.first)
            }
        }
        cancelCookie(request, response)
    }

    private fun resolveUsername(authentication: Authentication): String? {
        val principal = authentication.principal
        return if (principal is OAuth2User) {
            val email = principal.getAttribute<String>("email")
            val appUser = email?.let { appUserRepository.findByEmailIgnoreCase(it) }
            appUser?.username ?: email ?: authentication.name
        } else {
            authentication.name
        }
    }

    private fun isRememberMeRequested(request: HttpServletRequest, authentication: Authentication): Boolean {
        if (authentication is OAuth2AuthenticationToken) {
            return true
        }
        val param = request.getParameter("remember-me")
        return param != null && (param.equals("true", true) || param.equals("on", true) || param.equals("yes", true) || param == "1")
    }

    private fun extractRememberMeCookie(request: HttpServletRequest): Cookie? {
        return request.cookies?.find { it.name == COOKIE_NAME && !it.value.isNullOrBlank() }
    }

    private fun setRememberMeCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        series: String,
        rawToken: String,
        maxAgeSeconds: Int
    ) {
        val cookieValue = Base64.getUrlEncoder().withoutPadding().encodeToString("$series:$rawToken".toByteArray(StandardCharsets.UTF_8))
        val cookie = Cookie(COOKIE_NAME, cookieValue).apply {
            path = "/"
            isHttpOnly = true
            maxAge = maxAgeSeconds
            if (request.isSecure) {
                secure = true
            }
        }
        response.addCookie(cookie)
    }

    private fun cancelCookie(request: HttpServletRequest, response: HttpServletResponse) {
        val cookie = Cookie(COOKIE_NAME, "").apply {
            path = "/"
            isHttpOnly = true
            maxAge = 0
            if (request.isSecure) {
                secure = true
            }
        }
        response.addCookie(cookie)
    }

    private fun decodeCookie(cookieValue: String): Pair<String, String>? {
        val decoded = try {
            String(Base64.getUrlDecoder().decode(cookieValue), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            try {
                String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                return null
            }
        }
        val parts = decoded.split(":")
        if (parts.size != 2) return null
        return Pair(parts[0], parts[1])
    }

    private fun generateRandomString(length: Int): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(rawToken.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
