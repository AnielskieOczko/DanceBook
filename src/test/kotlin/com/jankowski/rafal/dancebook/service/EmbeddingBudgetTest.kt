package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun props(
        rpm: Int = 100, search: Int = 30, interactive: Int = 20,
        perUser: Int = 100, interactivePerUser: Int = 100, wait: Long = 2000
    ) = GoogleAiProperties(
        embeddingRequestsPerMinute = rpm,
        embeddingSearchSharePercent = search,
        embeddingInteractiveSharePercent = interactive,
        embeddingSearchPerUserPerMinute = perUser,
        embeddingInteractivePerUserPerMinute = interactivePerUser,
        embeddingInteractiveMaxWaitMs = wait
    )

    private fun budget(p: GoogleAiProperties = props()) = EmbeddingBudget(p, clock)

    @Test
    fun `searches may use only their share of the minute`() {
        val b = budget(props(rpm = 10, search = 30, interactive = 10))
        repeat(3) { assertTrue(b.tryAcquireForSearch(alice)) }
        assertFalse(b.tryAcquireForSearch(bob), "a 30 percent share of 10 is 3 searches")
    }

    @Test
    fun `one user cannot starve the others of the search share`() {
        val b = budget(props(perUser = 2))
        assertTrue(b.tryAcquireForSearch(alice))
        assertTrue(b.tryAcquireForSearch(alice))
        assertFalse(b.tryAcquireForSearch(alice))
        assertTrue(b.tryAcquireForSearch(bob))
    }

    @Test
    fun `the assistant has its own share and its own per-user cap, apart from the search box`() {
        val b = budget(props(rpm = 100, search = 10, interactive = 10, perUser = 1, interactivePerUser = 3))
        assertTrue(b.tryAcquireForSearch(alice))
        assertFalse(b.tryAcquireForSearch(alice), "search cap of 1 per user reached")
        // The assistant is unaffected by the exhausted search cap, and has its larger cap.
        repeat(3) { assertTrue(b.tryAcquireInteractive(alice, 0) { }) }
        assertFalse(b.tryAcquireInteractive(alice, 0) { })
    }

    @Test
    fun `assistant use cannot exhaust the search share and the reverse`() {
        val b = budget(props(rpm = 100, search = 5, interactive = 5))
        repeat(5) { assertTrue(b.tryAcquireInteractive(alice, 0) { }) }
        assertFalse(b.tryAcquireInteractive(bob, 0) { })
        repeat(5) { assertTrue(b.tryAcquireForSearch(bob), "search $it") }
    }

    @Test
    fun `a worker saturating the window still leaves searches and the assistant their shares`() {
        val b = budget(props(rpm = 10, search = 30, interactive = 20))
        // Worker cap is what is left: 10 - 3 - 2 = 5, taken without waiting.
        repeat(5) { b.acquireForWorker { throw AssertionError("worker must not wait yet") } }
        var waited = false
        b.acquireForWorker { ms -> waited = true; clock.now = clock.now.plusMillis(ms) }
        assertTrue(waited, "the sixth worker call must wait, it is capped")

        val fresh = budget(props(rpm = 10, search = 30, interactive = 20))
        repeat(5) { fresh.acquireForWorker { } }
        repeat(3) { assertTrue(fresh.tryAcquireForSearch(alice), "search $it must succeed") }
        repeat(2) { assertTrue(fresh.tryAcquireInteractive(bob, 0) { }, "assistant $it must succeed") }
    }

    @Test
    fun `an idle worker is not delayed`() {
        val b = budget(props(rpm = 10, search = 20, interactive = 20))
        var slept = 0L
        repeat(2) { b.acquireForWorker { slept += it } }
        assertEquals(0L, slept)
    }

    @Test
    fun `an interactive caller waits a bounded time for a slot and then gives up`() {
        val b = budget(props(rpm = 10, search = 10, interactive = 10))
        assertTrue(b.tryAcquireInteractive(alice, 0) { }) // takes the single interactive slot
        var slept = 0L
        val got = b.tryAcquireInteractive(bob, maxWaitMs = 1000) { ms -> slept += ms; clock.now = clock.now.plusMillis(ms) }
        assertFalse(got)
        assertTrue(slept in 1000..1250, "slept $slept")
    }

    @Test
    fun `an interactive caller proceeds once a slot frees within the wait`() {
        val b = budget(props(rpm = 10, search = 10, interactive = 10))
        assertTrue(b.tryAcquireInteractive(alice, 0) { })
        clock.now = clock.now.plusSeconds(59)
        assertTrue(b.tryAcquireInteractive(bob, maxWaitMs = 2000) { ms -> clock.now = clock.now.plusMillis(ms) })
    }

    @Test
    fun `an interactive caller with a free slot does not wait`() {
        val b = budget()
        assertTrue(b.tryAcquireInteractive(alice, 1000) { throw AssertionError("must not wait") })
    }

    @Test
    fun `slots free up after a minute`() {
        val b = budget(props(perUser = 1))
        assertTrue(b.tryAcquireForSearch(alice))
        assertFalse(b.tryAcquireForSearch(alice))
        clock.now = clock.now.plus(Duration.ofSeconds(61))
        assertTrue(b.tryAcquireForSearch(alice))
    }

    @Test
    fun `a limit of zero or less means unlimited for known users, like the worker always treated it`() {
        val b = budget(props(rpm = 0))
        repeat(500) { assertTrue(b.tryAcquireForSearch(alice)) }
        b.acquireForWorker { throw AssertionError("must not wait") }
    }

    @Test
    fun `anonymous callers never get semantic search or assistant embeddings`() {
        val b = budget()
        assertFalse(b.tryAcquireForSearch(null))
        assertFalse(b.tryAcquireInteractive(null, 0) { })
        assertTrue(b.tryAcquireForSearch(alice), "and they take nothing from signed-in users")
    }

    @Test
    fun `shares outside 5 to 95 percent are rejected at construction with a clear message`() {
        listOf(0, 3, 96, 100).forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { budget(props(search = bad)) }
            assertTrue(e.message!!.contains("embedding-search-share-percent"), e.message)
            assertThrows(IllegalArgumentException::class.java) { budget(props(interactive = bad)) }
        }
    }

    @Test
    fun `shares that leave the worker under 5 percent are rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) { budget(props(search = 60, interactive = 40)) }
        assertTrue(e.message!!.contains("worker"), e.message)
        budget(props(search = 50, interactive = 45)) // exactly 5 percent left is fine
    }
}
