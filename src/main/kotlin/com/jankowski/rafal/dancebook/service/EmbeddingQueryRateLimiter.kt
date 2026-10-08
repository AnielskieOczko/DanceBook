package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque

/**
 * Caps the embedding calls made while a user searches, using the same
 * `google.ai.embedding-requests-per-minute` setting as the index worker. The worker sleeps to
 * pace itself; a search must never wait, so this refuses instead and the caller falls back to
 * keyword-only results. Sliding one-minute window, in memory. A value of 0 or less is
 * unlimited, as it is for the worker.
 */
@Component
class EmbeddingQueryRateLimiter(
    private val googleAiProperties: GoogleAiProperties,
    private val clock: Clock
) {
    private val hits = ArrayDeque<Instant>()

    fun tryAcquire(): Boolean {
        val max = googleAiProperties.embeddingRequestsPerMinute
        if (max <= 0) return true
        val now = clock.instant()
        val cutoff = now.minusSeconds(60)
        synchronized(hits) {
            while (hits.isNotEmpty() && !hits.first.isAfter(cutoff)) hits.removeFirst()
            if (hits.size >= max) return false
            hits.addLast(now)
            return true
        }
    }
}
