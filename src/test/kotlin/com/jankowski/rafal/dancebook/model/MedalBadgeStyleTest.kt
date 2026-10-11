package com.jankowski.rafal.dancebook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MedalBadgeStyleTest {

    @Test
    fun `resolves enum from enum names case insensitively`() {
        assertEquals(MedalBadgeStyle.RANK_BARS, MedalBadgeStyle.fromStringOrNull("RANK_BARS"))
        assertEquals(MedalBadgeStyle.RANK_BARS, MedalBadgeStyle.fromStringOrNull("rank_bars"))
        assertEquals(MedalBadgeStyle.MEDAL_SHAPES, MedalBadgeStyle.fromStringOrNull("MEDAL_SHAPES"))
        assertEquals(MedalBadgeStyle.MEDAL_SHAPES, MedalBadgeStyle.fromStringOrNull("medal_shapes"))
        assertEquals(MedalBadgeStyle.RING_AND_WORD, MedalBadgeStyle.fromStringOrNull("RING_AND_WORD"))
        assertEquals(MedalBadgeStyle.RING_AND_WORD, MedalBadgeStyle.fromStringOrNull("ring_and_word"))
        assertEquals(MedalBadgeStyle.MEDAL_ICON, MedalBadgeStyle.fromStringOrNull("MEDAL_ICON"))
        assertEquals(MedalBadgeStyle.MEDAL_ICON, MedalBadgeStyle.fromStringOrNull("medal_icon"))
        assertEquals(MedalBadgeStyle.MEDAL_NO_NUMERALS, MedalBadgeStyle.fromStringOrNull("MEDAL_NO_NUMERALS"))
        assertEquals(MedalBadgeStyle.MEDAL_NO_NUMERALS, MedalBadgeStyle.fromStringOrNull("medal_no_numerals"))
    }

    @Test
    fun `resolves enum from mock direction codes`() {
        assertEquals(MedalBadgeStyle.RANK_BARS, MedalBadgeStyle.fromStringOrNull("A"))
        assertEquals(MedalBadgeStyle.RANK_BARS, MedalBadgeStyle.fromStringOrNull("a"))
        assertEquals(MedalBadgeStyle.MEDAL_SHAPES, MedalBadgeStyle.fromStringOrNull("B"))
        assertEquals(MedalBadgeStyle.MEDAL_SHAPES, MedalBadgeStyle.fromStringOrNull("b"))
        assertEquals(MedalBadgeStyle.RING_AND_WORD, MedalBadgeStyle.fromStringOrNull("C"))
        assertEquals(MedalBadgeStyle.RING_AND_WORD, MedalBadgeStyle.fromStringOrNull("c"))
        assertEquals(MedalBadgeStyle.MEDAL_ICON, MedalBadgeStyle.fromStringOrNull("D"))
        assertEquals(MedalBadgeStyle.MEDAL_ICON, MedalBadgeStyle.fromStringOrNull("d"))
        assertEquals(MedalBadgeStyle.MEDAL_NO_NUMERALS, MedalBadgeStyle.fromStringOrNull("D2"))
        assertEquals(MedalBadgeStyle.MEDAL_NO_NUMERALS, MedalBadgeStyle.fromStringOrNull("d2"))
    }

    @Test
    fun `returns null for null, empty or invalid input`() {
        assertNull(MedalBadgeStyle.fromStringOrNull(null))
        assertNull(MedalBadgeStyle.fromStringOrNull(""))
        assertNull(MedalBadgeStyle.fromStringOrNull("   "))
        assertNull(MedalBadgeStyle.fromStringOrNull("unknown_style"))
    }
}
