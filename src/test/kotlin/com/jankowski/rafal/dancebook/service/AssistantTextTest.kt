package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AssistantTextTest {

    @Test
    fun `each non-blank line becomes a div paragraph`() {
        assertEquals("<div>Feather step</div><div>Head drops</div>", AssistantText.toRichText("Feather step\n\n  Head drops  \n"))
    }

    @Test
    fun `markup in the text is escaped, never passed through`() {
        assertEquals(
            "<div>&lt;script&gt;alert(1)&lt;/script&gt; &amp; more</div>",
            AssistantText.toRichText("<script>alert(1)</script> & more")
        )
    }

    @Test
    fun `blank or missing text is null, so the field stays empty`() {
        assertNull(AssistantText.toRichText(null))
        assertNull(AssistantText.toRichText("  \n \r\n"))
    }
}
