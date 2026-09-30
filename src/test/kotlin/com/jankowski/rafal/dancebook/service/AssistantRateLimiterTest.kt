package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AssistantRateLimiterTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    @Test
    fun `allows twenty a minute per user and no more`() {
        val clock = MutableClock(Instant.parse("2026-09-29T10:00:00Z"))
        val limiter = AssistantRateLimiter(clock)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        repeat(20) { assertTrue(limiter.tryAcquire(a), "message ${it + 1}") }
        assertFalse(limiter.tryAcquire(a))
        assertTrue(limiter.tryAcquire(b), "another user is unaffected")
    }

    @Test
    fun `the window slides`() {
        val clock = MutableClock(Instant.parse("2026-09-29T10:00:00Z"))
        val limiter = AssistantRateLimiter(clock)
        val a = UUID.randomUUID()
        repeat(20) { limiter.tryAcquire(a) }
        assertFalse(limiter.tryAcquire(a))

        clock.now = clock.now.plus(Duration.ofSeconds(61))
        assertTrue(limiter.tryAcquire(a))
    }
}
