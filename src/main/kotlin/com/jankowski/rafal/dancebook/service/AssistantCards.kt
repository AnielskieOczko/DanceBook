package com.jankowski.rafal.dancebook.service

/** Small text helpers for turning search hits into cards. */
object AssistantCards {

    private const val MAX_TERMS = 6

    fun terms(query: String?): List<String> =
        query.orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.take(MAX_TERMS)

    /** A window of [plain] around the first term that occurs in it, or its start when none does. */
    fun snippet(plain: String?, terms: List<String>, radius: Int = 80): String? {
        val text = plain?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.length <= radius * 2) return text
        val lower = text.lowercase()
        val hit = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val from = if (hit == null) 0 else (hit - radius).coerceAtLeast(0)
        val to = (from + radius * 2).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to).trim() + (if (to < text.length) "…" else "")
    }
}
