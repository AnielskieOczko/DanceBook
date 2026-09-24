package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class ErrorPageIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @MockBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockBean
    private lateinit var calendarClient: GoogleCalendarClient

    @MockBean
    private lateinit var danceFigureService: DanceFigureService

    private val testUsername = "err-user"
    private val testPassword = "err-password"

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())
        `when`(danceFigureService.findAll(null, null, null, null, null, null)).thenReturn(emptyList())

        if (appUserRepository.findByUsername(testUsername) == null) {
            appUserRepository.save(AppUser().apply {
                username = testUsername
                email = "err-user@example.com"
                displayName = "Error Test User"
                password = passwordEncoder.encode(testPassword)
                role = Role.USER
            })
        }
    }

    private fun baseUrl(): String = "http://localhost:$port"

    private fun createClient(cookieManager: CookieManager? = null): HttpClient {
        val builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
        if (cookieManager != null) {
            builder.cookieHandler(cookieManager)
        }
        return builder.build()
    }

    @Test
    fun `unauthenticated request to nonexistent URL redirects to login`() {
        val client = createClient()
        val url = "${baseUrl()}/nonexistent-url-${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "text/html")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("Location").orElse("")
        assertTrue(location.endsWith("/login"), "Unauthenticated request to nonexistent URL must redirect to login")
    }

    @Test
    fun `unauthenticated request to mapped protected URL redirects to login`() {
        val client = createClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/materials"))
            .header("Accept", "text/html")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("Location").orElse("")
        assertTrue(location.endsWith("/login"), "Unauthenticated access to protected route must redirect to login")
    }

    @Test
    fun `authenticated nonexistent URL renders designed 404 page with navigation`() {
        val cookieManager = CookieManager()
        val client = createClient(cookieManager)
        login(client)

        val url = "${baseUrl()}/nonexistent-url-${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "text/html")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(404, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("Page Not Found"), "Response should contain 404 title")
        assertTrue(body.contains("Back to Dashboard"), "Response should contain navigation link back")
        assertTrue(body.contains("CHOREO"), "Response should contain brand header")
        assertTrue(body.contains("Notes"), "Authenticated response should contain top navbar links")
    }

    @Test
    fun `unhandled server exception renders designed 500 page without technical leakages`() {
        val cookieManager = CookieManager()
        val client = createClient(cookieManager)
        login(client)

        `when`(danceFigureService.findAll(null, null, null, null, null, null))
            .thenThrow(RuntimeException("Simulated unexpected server error"))

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/dance-figures"))
            .header("Accept", "text/html")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(500, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("Something Went Wrong"), "Response should contain 500 title")
        assertTrue(body.contains("Back to Dashboard"), "Response should contain navigation link back")
        assertFalse(body.contains("RuntimeException"), "Response must not expose exception class names")
        assertFalse(body.contains("Simulated unexpected server error"), "Response must not expose exception message")
        assertFalse(body.contains("DanceFigureWebController"), "Response must not expose controller class names")
        assertFalse(body.contains("at com.jankowski"), "Response must not expose stack traces")
    }

    @Test
    fun `direct error endpoint renders generic fallback without throwing`() {
        val client = createClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/error"))
            .header("Accept", "text/html")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        assertTrue(body.contains("Back to Dashboard"), "Fallback error page should render layout and link")
        assertFalse(body.contains("Whitelabel Error Page"), "Response must not be the Whitelabel error page")
    }

    private fun login(client: HttpClient) {
        val loginPageRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/login"))
            .header("Accept", "text/html")
            .GET()
            .build()
        val loginPageResponse = client.send(loginPageRequest, HttpResponse.BodyHandlers.ofString())

        val csrfRegex = Regex("""name="_csrf"\s+value="([^"]+)"""")
        val metaRegex = Regex("""name="_csrf"\s+content="([^"]+)"""")
        val csrfToken = (csrfRegex.find(loginPageResponse.body())?.groupValues?.get(1))
            ?: (metaRegex.find(loginPageResponse.body())?.groupValues?.get(1))
            ?: ""

        val formParams = "username=" + URLEncoder.encode(testUsername, StandardCharsets.UTF_8) +
                "&password=" + URLEncoder.encode(testPassword, StandardCharsets.UTF_8) +
                "&_csrf=" + URLEncoder.encode(csrfToken, StandardCharsets.UTF_8)

        val postRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "text/html")
            .POST(HttpRequest.BodyPublishers.ofString(formParams))
            .build()

        val postResponse = client.send(postRequest, HttpResponse.BodyHandlers.ofString())
        assertEquals(302, postResponse.statusCode(), "Login should redirect on success")
    }
}
