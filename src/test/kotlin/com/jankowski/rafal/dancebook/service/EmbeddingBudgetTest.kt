package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EmbeddingBudgetTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-08T10:00:00Z"))
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private fun budget(rpm: Int = 10, sharePercent: Int = 50, perUser: Int = 100) = EmbeddingBudget(
        GoogleAiProperties(
            embeddingRequestsPerMinute = rpm,
            embeddingSearchSharePercent = sharePercent,
            embeddingSearchPerUserPerMinute = perUser
        ),
        clock
    )

    @Test
    fun `searches may use only their share of the minute, leaving the rest to the worker`() {
        val b = budget(rpm = 10, sharePercent = 30)
        repeat(3) { assertTrue(b.tryAcquireForSearch(alice)) }
        assertFalse(b.tryAcquireForSearch(bob), "a 30 percent share of 10 is 3 searches")
    }

    @Test
    fun `one user cannot starve the others of the search share`() {
        val b = budget(rpm = 100, sharePercent = 50, perUser = 2)
        assertTrue(b.tryAcquireForSearch(alice))
        assertTrue(b.tryAcquireForSearch(alice))
        assertFalse(b.tryAcquireForSearch(alice))
        assertTrue(b.tryAcquireForSearch(bob))
    }

    @Test
    fun `a worker saturating the window still leaves searches their share`() {
        val b = budget(rpm = 10, sharePercent = 30)
        // The worker takes everything it is allowed without waiting: 70 percent of 10.
        repeat(7) { b.acquireForWorker { throw AssertionError("worker must not wait yet") } }
        // It cannot take more than that; the next call waits for the window to move.
        var waited = false
        b.acquireForWorker { ms -> waited = true; clock.now = clock.now.plusMillis(ms) }
        assertTrue(waited, "the eighth worker call must wait, it is capped at 70 percent")

        val fresh = budget(rpm = 10, sharePercent = 30)
        repeat(7) { fresh.acquireForWorker { } }
        repeat(3) { assertTrue(fresh.tryAcquireForSearch(alice), "search $it must succeed") }
        assertFalse(fresh.tryAcquireForSearch(alice))
    }

    @Test
    fun `an interactive caller waits a bounded time for a slot and then gives up`() {
        val b = budget(rpm = 10, sharePercent = 10, perUser = 100)
        assertTrue(b.tryAcquireForSearch(alice)) // takes the single search slot
        var slept = 0L
        val got = b.tryAcquireInteractive(bob, maxWaitMs = 1000) { ms -> slept += ms; clock.now = clock.now.plusMillis(ms) }
        assertFalse(got)
        assertTrue(slept in 1000..1250, "slept $slept")
    }

    @Test
    fun `an interactive caller proceeds once a slot frees within the wait`() {
        val b = budget(rpm = 10, sharePercent = 10)
        assertTrue(b.tryAcquireForSearch(alice))
        clock.now = clock.now.plusSeconds(59)
        assertTrue(b.tryAcquireInteractive(bob, maxWaitMs = 2000) { ms -> clock.now = clock.now.plusMillis(ms) })
    }

    @Test
    fun `an interactive caller with a free slot does not wait`() {
        val b = budget(rpm = 10, sharePercent = 50)
        assertTrue(b.tryAcquireInteractive(alice, maxWaitMs = 1000) { throw AssertionError("must not wait") })
    }

    @Test
    fun `search calls count against the worker, which waits for a slot instead of failing`() {
        val b = budget(rpm = 2, sharePercent = 100)
        assertTrue(b.tryAcquireForSearch(alice))
        assertTrue(b.tryAcquireForSearch(bob))
        var slept = 0L
        b.acquireForWorker { ms -> slept += ms; clock.now = clock.now.plusMillis(ms) }
        assertTrue(slept in 59_000..61_000, "waited $slept ms for the oldest search to leave the window")
    }

    @Test
    fun `an idle worker is not delayed`() {
        val b = budget(rpm = 10)
        var slept = 0L
        repeat(2) { b.acquireForWorker { slept += it } }
        assertEquals(0L, slept)
    }

    @Test
    fun `slots free up after a minute`() {
        val b = budget(rpm = 2, sharePercent = 100, perUser = 1)
        assertTrue(b.tryAcquireForSearch(alice))
        assertFalse(b.tryAcquireForSearch(alice))
        clock.now = clock.now.plus(Duration.ofSeconds(61))
        assertTrue(b.tryAcquireForSearch(alice))
    }

    @Test
    fun `a limit of zero or less means unlimited, like the worker always treated it`() {
        val b = budget(rpm = 0)
        repeat(500) { assertTrue(b.tryAcquireForSearch(alice)) }
        b.acquireForWorker { throw AssertionError("must not wait") }
    }

    @Test
    fun `an anonymous search is limited under one shared key`() {
        val b = budget(rpm = 100, sharePercent = 100, perUser = 1)
        assertTrue(b.tryAcquireForSearch(null))
        assertFalse(b.tryAcquireForSearch(null))
    }
}
