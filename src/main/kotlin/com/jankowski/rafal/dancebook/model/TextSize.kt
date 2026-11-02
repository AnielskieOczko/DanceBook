package com.jankowski.rafal.dancebook.model

/**
 * Per-user text size (#454). [percent] is the root font-size the stylesheet applies
 * for `data-text-size="<code>"`; everything sized in rem scales with it.
 */
enum class TextSize(val code: String, val percent: Int) {
    NORMAL("normal", 100),
    LARGE("large", 112),
    EXTRA_LARGE("extra_large", 125);

    companion object {
        val DEFAULT = NORMAL

        fun fromStringOrNull(value: String?): TextSize? {
            if (value.isNullOrBlank()) return null
            val v = value.trim()
            return entries.firstOrNull { it.name.equals(v, ignoreCase = true) || it.code.equals(v, ignoreCase = true) }
        }
    }
}
