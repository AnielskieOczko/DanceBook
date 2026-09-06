package com.jankowski.rafal.dancebook.service

import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A real 403 from Google carries a multi-kilobyte JSON document as its message. Rendering
 * that into a form field is what this translation exists to prevent.
 */
class CalendarSyncExceptionTest {

    @Test
    fun `should carry a concise message and keep the raw failure as the cause`() {
        val reason = "Google Calendar API has not been used in project 918960884147 before or it is disabled."
        val googleError = GoogleJsonError().apply {
            code = 403
            message = reason
        }
        val builder = HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders())
        val raw = GoogleJsonResponseException(builder, googleError)

        val translated = CalendarSyncException(
            "Google Calendar create failed (${raw.statusCode}): ${raw.details?.message}",
            raw
        )

        assertEquals("Google Calendar create failed (403): $reason", translated.message)
        assertSame(raw, translated.cause)

        // The point of the exercise: no JSON envelope leaks into the user-facing text.
        val message = translated.message!!
        assertFalse(message.contains("\"code\""))
        assertFalse(message.contains("@type"))
        assertTrue(message.length < 300)
    }

    @Test
    fun `should be unchecked so the compensating-delete path still catches it`() {
        // TrainingEventServiceImpl.create catches Exception to run its compensating
        // delete; an unchecked type keeps that path reachable without signature changes.
        val thrown: Throwable = CalendarSyncException("boom")
        assertTrue(thrown is RuntimeException)
    }
}
