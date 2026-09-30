package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * At most [MAX_PER_MINUTE] messages per user in any sliding minute. In memory: the app runs
 * as a single instance, and losing the counts on a restart only ever lets a burst through.
 */
@Component
@ConditionalOnAssistant
class AssistantRateLimiter(private val clock: Clock) {

    companion object {
        const val MAX_PER_MINUTE = 20
    }

    private val hits = ConcurrentHashMap<UUID, ArrayDeque<Instant>>()

    fun tryAcquire(userId: UUID): Boolean {
        val now = clock.instant()
        val cutoff = now.minusSeconds(60)
        val queue = hits.computeIfAbsent(userId) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && !queue.first.isAfter(cutoff)) queue.removeFirst()
            if (queue.size >= MAX_PER_MINUTE) return false
            queue.addLast(now)
            return true
        }
    }
}
