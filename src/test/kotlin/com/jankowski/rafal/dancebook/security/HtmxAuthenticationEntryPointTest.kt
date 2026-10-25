package com.jankowski.rafal.dancebook.security

import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.web.AuthenticationEntryPoint

class HtmxAuthenticationEntryPointTest {

    private val delegate = mock(AuthenticationEntryPoint::class.java)
    private val entryPoint = HtmxAuthenticationEntryPoint(delegate)

    @Test
    fun `commence sets HX-Redirect and 401 when HX-Request header is true`() {
        val request = MockHttpServletRequest()
        request.addHeader("HX-Request", "true")
        val response = MockHttpServletResponse()
        val exception = BadCredentialsException("expired")

        entryPoint.commence(request, response, exception)

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        assertEquals("/login", response.getHeader("HX-Redirect"))
    }

    @Test
    fun `commence delegates when HX-Request header is absent`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val exception = BadCredentialsException("expired")

        entryPoint.commence(request, response, exception)

        assertNull(response.getHeader("HX-Redirect"))
        verify(delegate).commence(request, response, exception)
    }
}
