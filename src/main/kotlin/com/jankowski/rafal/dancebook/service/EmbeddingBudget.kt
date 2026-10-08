package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * One per-minute budget of `google.ai.embedding-requests-per-minute` calls, split into three
 * independent pools so that none can starve another and together they stay under the provider's
 * limit:
 *
 * - **Search box** ([tryAcquireForSearch]): `embedding-search-share-percent` (default 30), at
 *   most `embedding-search-per-user-per-minute` (5) per user. Never waits; a refusal means
 *   keyword-only results.
 * - **Assistant** ([tryAcquireInteractive]): `embedding-interactive-share-percent` (default 20),
 *   at most `embedding-interactive-per-user-per-minute` (15) per user, since one question can use
 *   several. It may wait a short bounded time for a slot (the assistant is interactive); callers
 *   that must not wait, such as a page view, pass 0.
 * - **Index worker** ([acquireForWorker]): whatever is left (default 50). It waits when spent.
 *
 * Anonymous callers (no user id) get no semantic search and no assistant embeddings at all: a
 * single shared anonymous allowance would be used up by one visitor for everyone, and a public
 * visitor should not spend the paid budget. They still get keyword results.
 *
 * Each share must be 5 to 95 percent and together they must leave the worker at least 5, or
 * construction (so application startup) fails with a message naming the property.
 *
 * Sliding one-minute window, in memory. A requests-per-minute of 0 or less is unlimited.
 */
@Component
class EmbeddingBudget(
    private val googleAiProperties: GoogleAiProperties,
    private val clock: Clock
) {
    private enum class Pool { SEARCH, INTERACTIVE, WORKER }

    private class Entry(val at: Instant, val pool: Pool, val user: UUID?)

    private val entries = ArrayDeque<Entry>()

    init {
        val search = googleAiProperties.embeddingSearchSharePercent
        val interactive = googleAiProperties.embeddingInteractiveSharePercent
        require(search in 5..95) {
            "google.ai.embedding-search-share-percent must be between 5 and 95, was $search"
        }
        require(interactive in 5..95) {
            "google.ai.embedding-interactive-share-percent must be between 5 and 95, was $interactive"
        }
        require(search + interactive <= 95) {
            "google.ai.embedding-search-share-percent ($search) plus embedding-interactive-share-percent " +
                "($interactive) must leave at least 5 percent of the budget for the index worker"
        }
    }

    fun tryAcquireForSearch(userId: UUID?): Boolean =
        tryAcquire(Pool.SEARCH, userId, googleAiProperties.embeddingSearchSharePercent, googleAiProperties.embeddingSearchPerUserPerMinute)

    /** Like [tryAcquireForSearch] for the assistant, retrying every 100 ms for up to [maxWaitMs] before giving up. */
    fun tryAcquireInteractive(
        userId: UUID?,
        maxWaitMs: Long = googleAiProperties.embeddingInteractiveMaxWaitMs,
        sleep: (Long) -> Unit = { Thread.sleep(it) }
    ): Boolean {
        var waited = 0L
        while (true) {
            if (tryAcquire(Pool.INTERACTIVE, userId, googleAiProperties.embeddingInteractiveSharePercent, googleAiProperties.embeddingInteractivePerUserPerMinute)) return true
            if (userId == null || waited >= maxWaitMs) return false
            val step = minOf(100L, maxWaitMs - waited)
            sleep(step)
            waited += step
        }
    }

    private fun poolCap(total: Int, percent: Int) = maxOf(1, total * percent / 100)

    private fun tryAcquire(pool: Pool, userId: UUID?, percent: Int, perUser: Int): Boolean {
        if (userId == null) return false
        val total = googleAiProperties.embeddingRequestsPerMinute
        if (total <= 0) return true
        val cap = poolCap(total, percent)
        synchronized(entries) {
            val now = clock.instant()
            evict(now)
            val mine = entries.filter { it.pool == pool }
            if (entries.size >= total || mine.size >= cap) return false
            if (mine.count { it.user == userId } >= perUser) return false
            entries.addLast(Entry(now, pool, userId))
            return true
        }
    }

    /** Takes a slot, calling [sleep] with the time to wait for as long as the worker's pool is spent. */
    fun acquireForWorker(sleep: (Long) -> Unit = { Thread.sleep(it) }) {
        val total = googleAiProperties.embeddingRequestsPerMinute
        if (total <= 0) return
        val workerCap = maxOf(
            1,
            total - poolCap(total, googleAiProperties.embeddingSearchSharePercent) -
                poolCap(total, googleAiProperties.embeddingInteractiveSharePercent)
        )
        while (true) {
            val waitMs: Long
            synchronized(entries) {
                val now = clock.instant()
                evict(now)
                val mine = entries.filter { it.pool == Pool.WORKER }
                if (entries.size < total && mine.size < workerCap) {
                    entries.addLast(Entry(now, Pool.WORKER, null))
                    return
                }
                val blocking = if (mine.size >= workerCap) mine.first() else entries.first
                waitMs = maxOf(1L, Duration.between(now, blocking.at.plusSeconds(60)).toMillis())
            }
            sleep(waitMs)
        }
    }

    private fun evict(now: Instant) {
        val cutoff = now.minusSeconds(60)
        while (entries.isNotEmpty() && !entries.first.at.isAfter(cutoff)) entries.removeFirst()
    }
}
