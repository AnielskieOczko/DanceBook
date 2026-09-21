package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.RichTextLengthValidator
import jakarta.validation.ConstraintValidatorContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class RichTextServiceTest {

    private lateinit var service: RichTextService

    @BeforeEach
    fun setUp() {
        service = RichTextServiceImpl()
    }

    // --- Acceptance Criterion 1: Sanitisation & Security ---

    @Test
    fun `script tags are removed from stored and rendered content`() {
        val input = "Hello <script>alert('xss')</script>World"
        val cleaned = service.clean(input)
        val rendered = service.render(input)

        assertNotNull(cleaned)
        assertFalse(cleaned!!.contains("<script>"))
        assertFalse(cleaned.contains("alert"))
        assertEquals("Hello World", cleaned)

        assertNotNull(rendered)
        assertFalse(rendered!!.contains("<script>"))
        assertFalse(rendered.contains("alert"))
        assertEquals("Hello World", rendered)
    }

    @Test
    fun `event handler attributes are stripped from tags`() {
        val input = "<p onmouseover=\"alert('pwn')\">Hover me <strong onclick=\"evil()\">Bold</strong></p>"
        val cleaned = service.clean(input)
        val rendered = service.render(input)

        assertNotNull(cleaned)
        assertFalse(cleaned!!.contains("onmouseover"))
        assertFalse(cleaned.contains("onclick"))
        assertFalse(cleaned.contains("alert"))
        assertFalse(cleaned.contains("evil"))
        assertEquals("<p>Hover me <strong>Bold</strong></p>", cleaned)

        assertNotNull(rendered)
        assertFalse(rendered!!.contains("onmouseover"))
        assertFalse(rendered.contains("onclick"))
    }

    @Test
    fun `javascript pseudo-protocol links are stripped`() {
        val input = "<a href=\"javascript:alert('steal')\">Click here</a>"
        val cleaned = service.clean(input)
        val rendered = service.render(input)

        assertNotNull(cleaned)
        assertFalse(cleaned!!.contains("javascript:"))
        assertFalse(cleaned.contains("href"))
        assertEquals("Click here", cleaned)

        assertNotNull(rendered)
        assertFalse(rendered!!.contains("javascript:"))
    }

    @Test
    fun `disallowed tags such as images, iframes and tables are removed`() {
        val input = "<p>Text with <img src=\"x\" onerror=\"alert(1)\"> image and <iframe src=\"evil.html\"></iframe></p>"
        val cleaned = service.clean(input)

        assertNotNull(cleaned)
        assertFalse(cleaned!!.contains("<img"))
        assertFalse(cleaned.contains("<iframe"))
        assertFalse(cleaned.contains("onerror"))
        assertEquals("<p>Text with  image and </p>", cleaned)
    }

    @Test
    fun `outbound links automatically receive rel noopener noreferrer nofollow`() {
        val input = "<p>Visit <a href=\"https://example.com/dance\">Dance School</a> or <a href=\"http://dance.org\">Org</a></p>"
        val cleaned = service.clean(input)

        assertNotNull(cleaned)
        assertTrue(cleaned!!.contains("rel=\"noopener noreferrer nofollow\""))
        assertTrue(cleaned.contains("href=\"https://example.com/dance\""))
        assertTrue(cleaned.contains("href=\"http://dance.org\""))
    }

    @Test
    fun `mailto links are permitted without outbound rel`() {
        val input = "<a href=\"mailto:info@dancebook.org\">Email Us</a>"
        val cleaned = service.clean(input)

        assertNotNull(cleaned)
        assertEquals("<a href=\"mailto:info@dancebook.org\">Email Us</a>", cleaned)
    }

    // --- Acceptance Criterion 2: Legacy Plain-Text Handling ---

    @Test
    fun `legacy plain text notes render with preserved line breaks and escaped HTML entities`() {
        val legacyNotes = "First line\nSecond line\r\nThird line with a < b and c > d & \"characters\""
        val cleaned = service.clean(legacyNotes)
        val rendered = service.render(legacyNotes)

        // Clean preserves plain text on write for textarea editing
        assertEquals(legacyNotes.trim(), cleaned)

        // Render converts to safe HTML with <br> and escaped characters
        assertNotNull(rendered)
        assertTrue(rendered!!.contains("First line<br>Second line<br>Third line with a &lt; b and c &gt; d &amp; &quot;characters&quot;"))
    }

    @Test
    fun `valid rich text markup is preserved and not double-escaped on render`() {
        val richText = "<p>First paragraph with <strong>bold</strong></p><p>Second with <em>italic</em></p>"
        val rendered = service.render(richText)

        assertNotNull(rendered)
        assertEquals(richText, rendered)
    }

    // --- Acceptance Criterion 3: Structural Emptiness ---

    @Test
    fun `structurally empty markup persists as absent null`() {
        val emptyCases = listOf(
            null,
            "",
            "   ",
            "<p></p>",
            "<p><br></p>",
            "<p>&nbsp;</p>",
            "<p>   </p>",
            "<strong>   </strong>",
            "<ul><li></li></ul>",
            "<script>alert(1)</script>",
            "<p><script>alert(1)</script></p>",
            "<div></div>",
            "<div><br></div>",
            "<div>&nbsp;</div>",
            "<div>   </div>",
            "<div><!--block--></div>",
            "<div><!--block--><br></div>",
            "<div><!--block-->   </div>"
        )

        for (case in emptyCases) {
            assertNull(service.clean(case), "Expected clean to return null for '$case'")
            assertNull(service.render(case), "Expected render to return null for '$case'")
            assertNull(service.toPlainText(case), "Expected toPlainText to return null for '$case'")
        }
    }

    @Test
    fun `Trix div block structure is preserved and rendered cleanly`() {
        val trixMarkup = "<div>First line with <strong>bold</strong></div><div>Second line with <em>italic</em></div>"
        val cleaned = service.clean(trixMarkup)
        val rendered = service.render(trixMarkup)

        assertEquals(trixMarkup, cleaned)
        assertEquals(trixMarkup, rendered)
    }

    @Test
    fun `div attributes and event handlers are stripped by safelist`() {
        val hostileDiv = "<div class=\"malicious\" onclick=\"steal()\" style=\"color: red;\">Content</div>"
        val cleaned = service.clean(hostileDiv)

        assertEquals("<div>Content</div>", cleaned)
    }

    // --- Acceptance Criterion 4: Google Calendar Plain-Text Conversion ---

    @Test
    fun `rich text session description converts to readable plain text for Google Calendar`() {
        val description = "<p>Worked on <strong>Quickstep</strong>:</p><ul><li>Quarter turn</li><li>Progressive chasse</li></ul>"
        val plainText = service.toPlainText(description)

        assertNotNull(plainText)
        assertFalse(plainText!!.contains("<p>"))
        assertFalse(plainText.contains("<strong>"))
        assertFalse(plainText.contains("<ul>"))
        assertFalse(plainText.contains("<li>"))
        assertTrue(plainText.contains("Worked on Quickstep:"))
        assertTrue(plainText.contains("Quarter turn"))
        assertTrue(plainText.contains("Progressive chasse"))
    }

    // --- Acceptance Criterion 5: Excerpt Generation ---

    @Test
    fun `excerpt strips all markup and truncates on plain text character count`() {
        val richText = "<p>This is a <strong>formatted</strong> description that contains several words of detail.</p>"
        val excerpt = service.excerpt(richText, max = 25)

        assertNotNull(excerpt)
        assertFalse(excerpt!!.contains("<"))
        assertFalse(excerpt.contains(">"))
        assertEquals("This is a formatted descr...", excerpt)
    }

    @Test
    fun `excerpt returns full text when shorter than max`() {
        val richText = "<p>Short note.</p>"
        val excerpt = service.excerpt(richText, max = 50)

        assertNotNull(excerpt)
        assertEquals("Short note.", excerpt)
    }

    @Test
    fun `excerpt returns null for structurally empty input`() {
        assertNull(service.excerpt("<p></p>", max = 50))
        assertNull(service.excerpt("   ", max = 50))
        assertNull(service.excerpt(null, max = 50))
    }

    // --- Acceptance Criterion 7: Length Validation ---

    @Test
    fun `userTextLength counts typed plain text characters not markup bytes`() {
        val markup = "<p><strong>Hello</strong></p>"
        // Raw length is 29, but user typed "Hello" which is 5
        assertEquals(5, service.userTextLength(markup))
    }

    @Test
    fun `RichTextLengthValidator accepts markup when user text is within limit`() {
        val validator = RichTextLengthValidator(service)
        val dummyContext = mock(ConstraintValidatorContext::class.java)

        // 1990 characters of user text wrapped in markup (total raw length > 2000)
        val textBody = "a".repeat(1990)
        val formatted = "<p><strong>$textBody</strong></p>"

        assertTrue(formatted.length > 2000, "Raw length should exceed 2000")
        assertTrue(validator.isValid(formatted, dummyContext), "Should be valid because user text is <= 2000")
    }

    @Test
    fun `RichTextLengthValidator rejects input when user text exceeds limit`() {
        val validator = RichTextLengthValidator(service)
        val dummyContext = mock(ConstraintValidatorContext::class.java)

        val longText = "a".repeat(2001)
        assertFalse(validator.isValid(longText, dummyContext))
    }
}
