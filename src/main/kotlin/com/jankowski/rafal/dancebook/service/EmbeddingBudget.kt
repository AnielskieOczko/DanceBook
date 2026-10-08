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
 * - The worker ([acquireForWorker]) may use the whole budget and waits when it is spent.
 * - A search ([tryAcquireForSearch]) never waits: it is refused, and falls back to keyword-only
 *   results. It is refused when the budget is spent, when searches already hold their share of
 *   it (`embedding-search-share-percent`, default 30, so the worker always keeps the rest), or
 *   when that user has made `embedding-search-per-user-per-minute` searches (default 5), so one
 *   heavy user cannot use up everyone's share.
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
        val searchCap = maxOf(1, total * googleAiProperties.embeddingSearchSharePercent.coerceIn(0, 100) / 100)
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

    /** Takes a slot, calling [sleep] with the time to wait for as long as the budget is spent. */
    fun acquireForWorker(sleep: (Long) -> Unit = { Thread.sleep(it) }) {
        val total = googleAiProperties.embeddingRequestsPerMinute
        if (total <= 0) return
        while (true) {
            val waitMs: Long
            synchronized(entries) {
                val now = clock.instant()
                evict(now)
                if (entries.size < total) {
                    entries.addLast(Entry(now, null, false))
                    return
                }
                waitMs = maxOf(1L, Duration.between(now, entries.first.at.plusSeconds(60)).toMillis())
            }
            sleep(waitMs)
        }
    }

    private fun evict(now: Instant) {
        val cutoff = now.minusSeconds(60)
        while (entries.isNotEmpty() && !entries.first.at.isAfter(cutoff)) entries.removeFirst()
    }
}
