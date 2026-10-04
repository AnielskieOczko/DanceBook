package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.service.AppUserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Locale
import java.util.UUID

class UserLocaleResolverTest {

    private lateinit var appUserService: AppUserService
    private lateinit var resolver: UserLocaleResolver

    @BeforeEach
    fun setUp() {
        appUserService = mock(AppUserService::class.java)
        resolver = UserLocaleResolver(appUserService)
    }

    @Test
    fun `stored user locale takes precedence over Accept-Language header`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "polish_user"
            locale = "pl"
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)

        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "en-US,en;q=0.9")
        }

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.forLanguageTag("pl"), resolved)
    }

    @Test
    fun `stored user locale en takes precedence over Accept-Language pl`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "english_user"
            locale = "en"
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)

        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "pl-PL,pl;q=0.9")
        }

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.ENGLISH, resolved)
    }

    @Test
    fun `authenticated user without stored locale falls back to Accept-Language`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "no_locale_user"
            locale = null
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)

        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "pl-PL,pl;q=0.9")
        }

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.forLanguageTag("pl"), resolved)
    }

    @Test
    fun `authenticated user with blank locale falls back to Accept-Language`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "blank_locale_user"
            locale = "   "
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)

        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "pl")
        }

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.forLanguageTag("pl"), resolved)
    }

    @Test
    fun `anonymous user falls back to Accept-Language`() {
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(null)

        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "pl")
        }

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.forLanguageTag("pl"), resolved)
    }

    @Test
    fun `anonymous user defaults to English when no Accept-Language header present`() {
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(null)

        val request = MockHttpServletRequest()

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.ENGLISH, resolved)
    }

    @Test
    fun `unsupported Accept-Language falls back to default English`() {
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(null)

        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "de-DE,de;q=0.9")
        }

        val resolved = resolver.resolveLocale(request)

        assertEquals(Locale.ENGLISH, resolved)
    }

    @Test
    fun `setLocale updates locale for authenticated user`() {
        val userId = UUID.randomUUID()
        val user = AppUser().apply {
            id = userId
            username = "test_user"
            locale = "en"
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        resolver.setLocale(request, response, Locale.forLanguageTag("pl"))

        verify(appUserService).updateLocale(userId, "pl")
    }

    @Test
    fun `setLocale throws UnsupportedOperationException for anonymous user`() {
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(null)

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        assertThrows(UnsupportedOperationException::class.java) {
            resolver.setLocale(request, response, Locale.forLanguageTag("pl"))
        }
    }
}
