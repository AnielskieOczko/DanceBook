package com.jankowski.rafal.dancebook.controller.web

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeRedirectTest {

    @Test
    fun `sanitize rejects off-site and control character paths`() {
        assertNull(SafeRedirect.sanitize(null))
        assertNull(SafeRedirect.sanitize(""))
        assertNull(SafeRedirect.sanitize("https://evil.example"))
        assertNull(SafeRedirect.sanitize("relative/path"))
        assertNull(SafeRedirect.sanitize("//evil.example"))
        assertNull(SafeRedirect.sanitize("/\\evil.example"))
        assertNull(SafeRedirect.sanitize("/\tevil.example"))
        assertNull(SafeRedirect.sanitize("/foo\nbar"))
        assertNull(SafeRedirect.sanitize("/path\\with\\backslash"))
        assertNull(SafeRedirect.sanitize("/path\u007Fdel"))
    }

    @Test
    fun `sanitize accepts same-site paths including a query string`() {
        assertEquals("/", SafeRedirect.sanitize("/"))
        assertEquals("/training-events", SafeRedirect.sanitize("/training-events"))
        assertEquals(
            "/training-events/timeline?page=1&lastMonth=listopad",
            SafeRedirect.sanitize("/training-events/timeline?page=1&lastMonth=listopad")
        )
    }

    @Test
    fun `orDefault falls back for a rejected target and keeps a valid one`() {
        assertEquals("/profile", SafeRedirect.orDefault("//evil.example", "/profile"))
        assertEquals("/profile", SafeRedirect.orDefault("  ", "/profile"))
        assertEquals("/training-events", SafeRedirect.orDefault("/training-events", "/profile"))
    }
}
