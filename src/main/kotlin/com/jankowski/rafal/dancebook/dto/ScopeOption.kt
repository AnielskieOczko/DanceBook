package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.SeriesScope

/**
 * An option presented in the delete scope dialog for a repeating series.
 */
data class ScopeOption(
    val scope: SeriesScope,
    val label: String = scope.displayName,
    val count: Int,
    val outcomeCount: Int,
    val description: String = formatDescription(count, outcomeCount)
) {
    companion object {
        fun formatDescription(count: Int, outcomeCount: Int): String {
            val sessionText = if (count == 1) "1 session" else "$count sessions"
            if (outcomeCount <= 0) return sessionText
            val outcomeText = if (outcomeCount == 1) "1 with recorded outcome" else "$outcomeCount with recorded outcomes"
            return "$sessionText ($outcomeText)"
        }
    }
}
