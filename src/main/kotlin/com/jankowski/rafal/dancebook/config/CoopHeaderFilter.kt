package com.jankowski.rafal.dancebook.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

/**
 * Adds Cross-Origin-Opener-Policy header to allow Google Identity Services
 * popup window to communicate back with our page after OAuth sign-in.
 * 
 * Without this, Chrome blocks the popup from reporting the token back,
 * causing "Cross-Origin-Opener-Policy policy would block the window.closed call" error.
 */
@Component
class CoopHeaderFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        (response as HttpServletResponse).setHeader(
            "Cross-Origin-Opener-Policy", "same-origin-allow-popups"
        )
        chain.doFilter(request, response)
    }
}
