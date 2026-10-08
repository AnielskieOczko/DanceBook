package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class EmbeddingQueryRateLimiterTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    @Test
    fun `allows the configured number of requests per minute then refuses`() {
        val clock = MutableClock(Instant.parse("2026-10-08T10:00:00Z"))
        val limiter = EmbeddingQueryRateLimiter(GoogleAiProperties(embeddingRequestsPerMinute = 2), clock)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `frees a slot once a minute has passed`() {
        val clock = MutableClock(Instant.parse("2026-10-08T10:00:00Z"))
        val limiter = EmbeddingQueryRateLimiter(GoogleAiProperties(embeddingRequestsPerMinute = 1), clock)

        assertTrue(limiter.tryAcquire())
        clock.now = clock.now.plus(Duration.ofSeconds(30))
        assertFalse(limiter.tryAcquire())
        clock.now = clock.now.plus(Duration.ofSeconds(31))
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `a limit of zero or less means unlimited, like the index worker`() {
        val limiter = EmbeddingQueryRateLimiter(GoogleAiProperties(embeddingRequestsPerMinute = 0), Clock.systemUTC())
        repeat(500) { assertTrue(limiter.tryAcquire()) }
    }
}
