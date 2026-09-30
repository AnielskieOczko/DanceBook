package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssistantCardsTest {

    @Test
    fun `terms are lowercase words, capped at six`() {
        assertEquals(listOf("sway", "natural", "turn"), AssistantCards.terms("  Sway  NATURAL turn "))
        assertEquals(6, AssistantCards.terms("a b c d e f g h").size)
        assertEquals(emptyList<String>(), AssistantCards.terms(null))
        assertEquals(emptyList<String>(), AssistantCards.terms("   "))
    }

    @Test
    fun `snippet centres on the first matching term with ellipses`() {
        val text = "x".repeat(200) + " there is no sway on step one " + "y".repeat(200)
        val snippet = AssistantCards.snippet(text, listOf("sway"), radius = 20)!!
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("sway"))
        assertTrue(snippet.length < 60)
    }

    @Test
    fun `a short text is returned whole, and no text gives no snippet`() {
        assertEquals("sway on two", AssistantCards.snippet("sway on two", listOf("sway")))
        assertNull(AssistantCards.snippet(null, listOf("sway")))
        assertNull(AssistantCards.snippet("   ", listOf("sway")))
    }

    @Test
    fun `with no matching term the snippet is the start of the text`() {
        val snippet = AssistantCards.snippet("z".repeat(500), listOf("sway"), radius = 30)!!
        assertTrue(snippet.startsWith("zzz"))
        assertTrue(snippet.endsWith("…"))
    }
}
