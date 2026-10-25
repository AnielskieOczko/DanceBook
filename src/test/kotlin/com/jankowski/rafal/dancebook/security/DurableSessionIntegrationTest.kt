package com.jankowski.rafal.dancebook.security

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key="])
class DurableSessionIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var persistentLoginRepository: PersistentLoginRepository

    @Autowired
    private lateinit var durableRememberMeServices: DurableRememberMeServices

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var calendarClient: GoogleCalendarClient

    private val testUsername = "durable_user"
    private val testPassword = "testPassword123!"
    private val testEmail = "durable@dancebook.test"

    @BeforeEach
    fun setUp() {
        persistentLoginRepository.deleteByUsername(testUsername)
        persistentLoginRepository.deleteByUsername("google_durable_user")
        persistentLoginRepository.deleteByUsername("google_durable@dancebook.test")

        if (appUserRepository.findByUsername(testUsername) == null) {
            val user = AppUser().apply {
                this.username = testUsername
                this.password = passwordEncoder.encode(testPassword)
                this.email = testEmail
                this.displayName = "Durable User"
                this.role = Role.USER
            }
            appUserRepository.save(user)
        }
    }

    @Test
    fun `form login with remember-me ticked persists across app restart and survives browser close`() {
        // 1. Form login with remember-me = true
        val loginResult = mockMvc.perform(
            post("/login")
                .param("username", testUsername)
                .param("password", testPassword)
                .param("remember-me", "true")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))
            .andReturn()

        val rememberMeCookie = loginResult.response.getCookie("remember-me")
        assertNotNull(rememberMeCookie, "remember-me cookie must be issued")
        assertTrue(rememberMeCookie!!.maxAge > 0, "remember-me cookie must have a positive maxAge (weeks)")
        assertEquals(28 * 24 * 3600, rememberMeCookie.maxAge)

        // Verify token in database
        val series = decodeSeries(rememberMeCookie.value)
        val tokenRecord = persistentLoginRepository.findBySeries(series)
        assertNotNull(tokenRecord)
        assertEquals(testUsername, tokenRecord!!.username)
        assertTrue(tokenRecord.rememberMe)

        // 2. Simulate app restart: NO session passed, only the remember-me cookie
        val restartResult = mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", rememberMeCookie.value))
        )
            .andExpect(status().isOk)
            .andReturn()

        // Verify rotated token was sent back
        val rotatedCookie = restartResult.response.getCookie("remember-me")
        assertNotNull(rotatedCookie, "Rotated remember-me cookie should be returned")

        // 3. Simulate closing and reopening browser: persistent cookie is retained by browser
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", rotatedCookie!!.value))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `form login with remember-me unticked survives app restart but does not survive browser close`() {
        // 1. Form login with remember-me unticked (no remember-me parameter)
        val loginResult = mockMvc.perform(
            post("/login")
                .param("username", testUsername)
                .param("password", testPassword)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))
            .andReturn()

        val rememberMeCookie = loginResult.response.getCookie("remember-me")
        assertNotNull(rememberMeCookie, "remember-me cookie must be issued for unticked session durability")
        assertEquals(-1, rememberMeCookie!!.maxAge, "Unticked cookie must be a session cookie (maxAge = -1)")

        val series = decodeSeries(rememberMeCookie.value)
        val tokenRecord = persistentLoginRepository.findBySeries(series)
        assertNotNull(tokenRecord)
        assertEquals(testUsername, tokenRecord!!.username)
        assertEquals(false, tokenRecord.rememberMe)

        // 2. Simulate app restart while browser remains open (browser sends session cookie)
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", rememberMeCookie.value))
        )
            .andExpect(status().isOk)

        // 3. Simulate closing the browser: session cookies are discarded by the browser
        // A reopened browser sends NO cookies -> unauthenticated request
        mockMvc.perform(
            get("/dance-figures")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))
    }

    @Test
    fun `concurrent requests with same old cookie within grace period succeed and other series remain untouched`() {
        // Create an existing series B for the same user (e.g. from mobile device)
        val otherSeries = "mobile-device-series-888"
        val otherTokenHash = hashToken("mobile-token-hash")
        persistentLoginRepository.save(
            PersistentLoginToken(
                series = otherSeries,
                username = testUsername,
                token = otherTokenHash,
                previousToken = null,
                lastUsed = Instant.now(),
                rememberMe = true,
                expiresAt = Instant.now().plusSeconds(3600)
            )
        )

        // Login to get primary remember-me cookie (series A)
        val loginResult = mockMvc.perform(
            post("/login")
                .param("username", testUsername)
                .param("password", testPassword)
                .param("remember-me", "true")
                .with(csrf())
        ).andReturn()
        val originalCookie = loginResult.response.getCookie("remember-me")!!
        val originalSeries = decodeSeries(originalCookie.value)

        // Request 1 arrives after restart: rotates token
        val res1 = mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", originalCookie.value))
        ).andExpect(status().isOk).andReturn()

        val rotatedCookie = res1.response.getCookie("remember-me")!!
        assertTrue(rotatedCookie.value != originalCookie.value, "Token must be rotated")

        // Request 2 arrives immediately with the SAME old cookie (e.g. second tab / parallel request)
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", originalCookie.value))
        ).andExpect(status().isOk)

        // Verify other series on mobile device was untouched!
        val otherRecord = persistentLoginRepository.findBySeries(otherSeries)
        assertNotNull(otherRecord, "Other device series must not be deleted by parallel requests")
        assertEquals(testUsername, otherRecord!!.username)

        // Primary series also still exists
        val primaryRecord = persistentLoginRepository.findBySeries(originalSeries)
        assertNotNull(primaryRecord, "Primary series must remain valid")
    }

    @Test
    fun `genuine token mismatch rejects request and deletes only that series, leaving other series untouched`() {
        val seriesToCompromise = "compromised-series-111"
        val legitimateSeries = "legitimate-series-222"

        val rawValidToken = "legitimate-token-value"
        val rawCompromisedToken = "actual-token-value"
        val fakeToken = "attacker-forged-token"

        persistentLoginRepository.save(
            PersistentLoginToken(
                series = legitimateSeries,
                username = testUsername,
                token = hashToken(rawValidToken),
                previousToken = null,
                lastUsed = Instant.now(),
                rememberMe = true,
                expiresAt = Instant.now().plusSeconds(3600)
            )
        )

        persistentLoginRepository.save(
            PersistentLoginToken(
                series = seriesToCompromise,
                username = testUsername,
                token = hashToken(rawCompromisedToken),
                previousToken = null,
                lastUsed = Instant.now(),
                rememberMe = true,
                expiresAt = Instant.now().plusSeconds(3600)
            )
        )

        val badCookieValue = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$seriesToCompromise:$fakeToken".toByteArray())

        // Request with mismatched token
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", badCookieValue))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))

        // Compromised series is deleted
        assertNull(persistentLoginRepository.findBySeries(seriesToCompromise), "Compromised series must be deleted")

        // Legitimate series on other device remains valid and untouched
        val legitimateRecord = persistentLoginRepository.findBySeries(legitimateSeries)
        assertNotNull(legitimateRecord, "Legitimate series must NOT be deleted")
        assertEquals(testUsername, legitimateRecord!!.username)
    }

    @Test
    fun `expired rows for user are purged upon sign in`() {
        val expiredSeries = "expired-series-old"
        val validOtherSeries = "valid-series-live"

        persistentLoginRepository.save(
            PersistentLoginToken(
                series = expiredSeries,
                username = testUsername,
                token = "expired-token-hash",
                previousToken = null,
                lastUsed = Instant.now().minusSeconds(7200),
                rememberMe = false,
                expiresAt = Instant.now().minusSeconds(3600) // expired 1 hour ago
            )
        )

        persistentLoginRepository.save(
            PersistentLoginToken(
                series = validOtherSeries,
                username = testUsername,
                token = "valid-token-hash",
                previousToken = null,
                lastUsed = Instant.now(),
                rememberMe = true,
                expiresAt = Instant.now().plusSeconds(7200) // active
            )
        )

        assertNotNull(persistentLoginRepository.findBySeries(expiredSeries))

        // Sign in as testUser
        mockMvc.perform(
            post("/login")
                .param("username", testUsername)
                .param("password", testPassword)
                .param("remember-me", "true")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))

        // Expired row must have been purged
        assertNull(persistentLoginRepository.findBySeries(expiredSeries), "Expired series must be purged during login")

        // Unexpired row must remain intact
        assertNotNull(persistentLoginRepository.findBySeries(validOtherSeries), "Active series must remain intact")
    }

    @Test
    fun `Google OAuth2 login issues durable token and survives app restart`() {
        val googleEmail = "google_durable@dancebook.test"
        val googleUsername = "google_durable_user"

        if (appUserRepository.findByUsername(googleUsername) == null) {
            val user = AppUser().apply {
                this.username = googleUsername
                this.email = googleEmail
                this.displayName = "Google User"
                this.role = Role.USER
            }
            appUserRepository.save(user)
        }

        // Simulate OAuth2 authentication
        val oauth2User = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf("email" to googleEmail, "email_verified" to true),
            "email"
        )
        val oauth2Auth = OAuth2AuthenticationToken(oauth2User, listOf(SimpleGrantedAuthority("ROLE_USER")), "google")

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val handler = OAuth2RememberMeSuccessHandler(durableRememberMeServices, "/")
        handler.onAuthenticationSuccess(request, response, oauth2Auth)

        val cookie = response.getCookie("remember-me")
        assertNotNull(cookie, "Google login must issue remember-me cookie")
        assertTrue(cookie!!.maxAge > 0, "Google login cookie must have positive maxAge")

        val series = decodeSeries(cookie.value)
        val record = persistentLoginRepository.findBySeries(series)
        assertNotNull(record)
        assertEquals(googleUsername, record!!.username, "Stored username must match internal user account")
        assertTrue(record.rememberMe)

        // Simulate app restart: request with ONLY the remember-me cookie
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", cookie.value))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `idle polling does not write to the database`() {
        // Sign in to establish session
        val session = MockHttpSession()
        val loginResult = mockMvc.perform(
            post("/login")
                .param("username", testUsername)
                .param("password", testPassword)
                .param("remember-me", "true")
                .session(session)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val rememberMeCookie = loginResult.response.getCookie("remember-me")!!
        val series = decodeSeries(rememberMeCookie.value)
        val initialTokenRecord = persistentLoginRepository.findBySeries(series)!!

        // Poll /notifications/count 5 times with the active session
        for (i in 1..5) {
            mockMvc.perform(
                get("/notifications/count")
                    .session(session)
                    .cookie(Cookie("remember-me", rememberMeCookie.value))
            )
                .andExpect(status().isOk)
        }

        // Verify that persistent_logins was NOT written to during polls
        val afterPollsTokenRecord = persistentLoginRepository.findBySeries(series)!!
        assertEquals(initialTokenRecord.token, afterPollsTokenRecord.token, "Token must not be modified during idle polls")
        assertEquals(initialTokenRecord.lastUsed, afterPollsTokenRecord.lastUsed, "last_used timestamp must not change during idle polls")
        assertEquals(initialTokenRecord.expiresAt, afterPollsTokenRecord.expiresAt, "expires_at must not change during idle polls")
    }

    @Test
    fun `signing out really signs out and stored token cannot be reused`() {
        val session = MockHttpSession()
        val loginResult = mockMvc.perform(
            post("/login")
                .param("username", testUsername)
                .param("password", testPassword)
                .param("remember-me", "true")
                .session(session)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val rememberMeCookie = loginResult.response.getCookie("remember-me")!!
        val series = decodeSeries(rememberMeCookie.value)
        assertNotNull(persistentLoginRepository.findBySeries(series))

        // Confirm authenticated access works
        mockMvc.perform(
            get("/dance-figures")
                .session(session)
                .cookie(Cookie("remember-me", rememberMeCookie.value))
        )
            .andExpect(status().isOk)

        // Perform POST /logout (simulating user logout or inactivity auto-logout)
        val logoutResult = mockMvc.perform(
            post("/logout")
                .session(session)
                .cookie(Cookie("remember-me", rememberMeCookie.value))
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login?logout"))
            .andReturn()

        // Verify cookie is cancelled
        val clearedCookie = logoutResult.response.getCookie("remember-me")
        assertNotNull(clearedCookie)
        assertEquals(0, clearedCookie!!.maxAge, "Cookie must be cleared with maxAge 0")

        // Verify token in DB is removed
        assertNull(persistentLoginRepository.findBySeries(series), "Token record must be deleted from DB on logout")

        // Attempt to access again with the old remember-me cookie -> must redirect to login
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", rememberMeCookie.value))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))

        // Attempt to access again with the invalidated session -> must redirect to login
        mockMvc.perform(
            get("/dance-figures")
                .session(session)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))
    }

    @Test
    fun `expired unticked token is rejected and deleted`() {
        // Create an already-expired unticked token
        val series = "expired-series-999"
        val rawToken = "raw-token-999"

        persistentLoginRepository.save(
            PersistentLoginToken(
                series = series,
                username = testUsername,
                token = hashToken(rawToken),
                previousToken = null,
                lastUsed = Instant.now().minusSeconds(3600),
                rememberMe = false,
                expiresAt = Instant.now().minusSeconds(1800) // expired 30 mins ago
            )
        )

        val cookieValue = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$series:$rawToken".toByteArray())

        // Access with expired cookie
        mockMvc.perform(
            get("/dance-figures")
                .cookie(Cookie("remember-me", cookieValue))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))

        // Expired token was cleaned up
        assertNull(persistentLoginRepository.findBySeries(series))
    }

    private fun decodeSeries(cookieValue: String): String {
        val decoded = String(java.util.Base64.getUrlDecoder().decode(cookieValue))
        return decoded.substringBefore(":")
    }

    private fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(rawToken.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
