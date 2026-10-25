package com.jankowski.rafal.dancebook.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint

/**
 * An [AuthenticationEntryPoint] that handles unauthenticated requests from HTMX by returning
 * an `HX-Redirect: /login` response with status 401 (Unauthorized), instructing the browser to
 * perform a full-page navigation to the login page rather than swapping the login form into
 * partial page fragments.
 *
 * For non-HTMX requests, it delegates to [delegate] (by default [LoginUrlAuthenticationEntryPoint]),
 * issuing a standard HTTP 302 redirect to `/login`.
 */
class HtmxAuthenticationEntryPoint(
    private val delegate: AuthenticationEntryPoint = LoginUrlAuthenticationEntryPoint("/login")
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        if ("true".equals(request.getHeader("HX-Request"), ignoreCase = true)) {
            response.setHeader("HX-Redirect", "/login")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
        } else {
            delegate.commence(request, response, authException)
        }
    }
}
