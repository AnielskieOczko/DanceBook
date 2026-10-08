package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * One per-minute budget of `google.ai.embedding-requests-per-minute` calls, drawn on by both the
 * index worker and live searches, so together they stay under the provider's limit.
 *
 * The budget is split by `embedding-search-share-percent` (default 30):
 * - The worker ([acquireForWorker]) may use the other 70 percent and waits when that is spent.
 *   Its calls alone can therefore never fill the window and starve searches.
 * - Searches ([tryAcquireForSearch]) and the assistant ([tryAcquireInteractive]) share the
 *   search part. A search never waits: it is refused, and falls back to keyword-only results.
 *   It is also refused when that user has made `embedding-search-per-user-per-minute` calls
 *   (default 5), so one heavy user cannot use up everyone's share. The assistant is interactive,
 *   so it waits a short, bounded time for a slot before giving up.
 *
 * Sliding one-minute window, in memory. A requests-per-minute of 0 or less is unlimited.
 */
@Component
class EmbeddingBudget(
    private val googleAiProperties: GoogleAiProperties,
    private val clock: Clock
) {
    private class Entry(val at: Instant, val searchUser: Any?, val isSearch: Boolean)

    private val anonymous = Any()
    private val entries = ArrayDeque<Entry>()

    fun tryAcquireForSearch(userId: UUID?): Boolean {
        val total = googleAiProperties.embeddingRequestsPerMinute
        if (total <= 0) return true
        val key: Any = userId ?: anonymous
        val searchCap = searchCap(total)
        synchronized(entries) {
            val now = clock.instant()
            evict(now)
            val searches = entries.filter { it.isSearch }
            if (entries.size >= total) return false
            if (searches.size >= searchCap) return false
            if (searches.count { it.searchUser == key } >= googleAiProperties.embeddingSearchPerUserPerMinute) return false
            entries.addLast(Entry(now, key, true))
            return true
        }
    }

    /** Like [tryAcquireForSearch], but retries every 100 ms for up to [maxWaitMs] before giving up. */
    fun tryAcquireInteractive(userId: UUID?, maxWaitMs: Long = googleAiProperties.embeddingInteractiveMaxWaitMs, sleep: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        var waited = 0L
        while (true) {
            if (tryAcquireForSearch(userId)) return true
            if (waited >= maxWaitMs) return false
            val step = minOf(100L, maxWaitMs - waited)
            sleep(step)
            waited += step
        }
    }

    private fun searchCap(total: Int) =
        maxOf(1, total * googleAiProperties.embeddingSearchSharePercent.coerceIn(0, 100) / 100)

    /** Takes a slot, calling [sleep] with the time to wait for as long as the budget is spent. */
    fun acquireForWorker(sleep: (Long) -> Unit = { Thread.sleep(it) }) {
        val total = googleAiProperties.embeddingRequestsPerMinute
        if (total <= 0) return
        while (true) {
            val waitMs: Long
            synchronized(entries) {
                val now = clock.instant()
                evict(now)
                val workerCap = maxOf(1, total - searchCap(total))
                val mine = entries.filter { !it.isSearch }
                if (entries.size < total && mine.size < workerCap) {
                    entries.addLast(Entry(now, null, false))
                    return
                }
                // Wait for the oldest entry that is actually blocking us to leave the window.
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
