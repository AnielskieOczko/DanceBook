package com.jankowski.rafal.dancebook.model

/**
 * Approved visual representations for medal badges (#292), as defined in
 * docs/mocks/figures/index.html.
 */
enum class MedalBadgeStyle(val code: String, val dir: String) {
    RANK_BARS("rank_bars", "A"),
    MEDAL_SHAPES("medal_shapes", "B"),
    RING_AND_WORD("ring_and_word", "C"),
    MEDAL_ICON("medal_icon", "D"),
    MEDAL_NO_NUMERALS("medal_no_numerals", "D2");

    val displayName: String get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        val DEFAULT = RANK_BARS

        fun fromStringOrNull(value: String?): MedalBadgeStyle? {
            if (value.isNullOrBlank()) return null
            val v = value.trim()
            return entries.firstOrNull {
                it.name.equals(v, ignoreCase = true) ||
                    it.code.equals(v, ignoreCase = true) ||
                    it.dir.equals(v, ignoreCase = true)
            }
        }
    }
}
