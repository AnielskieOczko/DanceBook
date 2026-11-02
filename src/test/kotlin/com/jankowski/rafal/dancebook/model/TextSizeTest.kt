package com.jankowski.rafal.dancebook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TextSizeTest {
    @Test
    fun `parses name or code case-insensitively`() {
        assertEquals(TextSize.EXTRA_LARGE, TextSize.fromStringOrNull("EXTRA_LARGE"))
        assertEquals(TextSize.LARGE, TextSize.fromStringOrNull(" large "))
    }

    @Test
    fun `blank and unknown values are null`() {
        assertNull(TextSize.fromStringOrNull(null))
        assertNull(TextSize.fromStringOrNull(""))
        assertNull(TextSize.fromStringOrNull("huge"))
    }

    @Test
    fun `sizes grow monotonically from 100 percent`() {
        assertEquals(100, TextSize.NORMAL.percent)
        assert(TextSize.LARGE.percent in 101 until TextSize.EXTRA_LARGE.percent)
    }
}
