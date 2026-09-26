package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against regressions in the Trix rich text editor configuration and toolbar pruning.
 *
 * Issue #140: `main.js` is loaded synchronously in `<head>` (via `layout.html`), where
 * `document.body` is null. Binding event listeners to `document.body` at top-level throws an
 * uncaught `TypeError: Cannot read properties of null (reading 'addEventListener')`, which halts
 * script execution and prevents all downstream code — including `trix-initialize` toolbar pruning
 * and `trix-file-accept` file rejection — from registering. This causes all 14 Trix controls
 * to remain in the toolbar as blank grey squares.
 *
 * Event listeners in `main.js` must be bound to `document` rather than `document.body`.
 */
class RichTextToolbarGuardTest {

    private val mainJsFile = File("src/main/resources/static/js/main.js")
    private val templatesDir = File("src/main/resources/templates")

    private val disallowedSelectors = listOf(
        """[data-trix-attribute="strike"]""",
        """[data-trix-attribute="heading1"]""",
        """[data-trix-attribute="quote"]""",
        """[data-trix-attribute="code"]""",
        """[data-trix-action="decreaseNestingLevel"]""",
        """[data-trix-action="increaseNestingLevel"]""",
        """[data-trix-button-group="file-tools"]""",
        """[data-trix-button-group="history-tools"]"""
    )

    @Test
    fun `main js does not attach event listeners to document body`() {
        assertTrue(mainJsFile.isFile, "main.js not found at $mainJsFile")
        val content = mainJsFile.readText()

        // document.body.addEventListener at top-level throws TypeError when main.js executes in <head>
        val bodyEventListenerPattern = Regex("""document\.body\.addEventListener""")
        val matches = bodyEventListenerPattern.findAll(content).toList()

        assertTrue(
            matches.isEmpty(),
            "Found `document.body.addEventListener` in main.js. `main.js` is loaded in <head> " +
                "where `document.body` is null at evaluation time. Any top-level listener on `document.body` " +
                "throws TypeError and halts script execution before subsequent code (such as Trix toolbar pruning) " +
                "can run. Attach listeners to `document` instead."
        )
    }

    @Test
    fun `main js registers trix-initialize and trix-file-accept on document`() {
        assertTrue(mainJsFile.isFile, "main.js not found at $mainJsFile")
        val content = mainJsFile.readText()

        assertTrue(
            content.contains("document.addEventListener('trix-initialize'"),
            "main.js must register the 'trix-initialize' listener on `document` to prune disallowed toolbar controls."
        )
        assertTrue(
            content.contains("document.addEventListener('trix-file-accept'"),
            "main.js must register the 'trix-file-accept' listener on `document` to reject file attachments."
        )
    }

    @Test
    fun `main js prunes all disallowed trix controls`() {
        assertTrue(mainJsFile.isFile, "main.js not found at $mainJsFile")
        val content = mainJsFile.readText()

        val missingSelectors = disallowedSelectors.filterNot { content.contains(it) }
        assertTrue(
            missingSelectors.isEmpty(),
            "main.js is missing toolbar pruning selectors for disallowed Trix controls: $missingSelectors. " +
                "The rich text toolbar must prune these controls so only bold, italic, link, " +
                "bulleted list, and numbered list remain."
        )
    }

    @Test
    fun `all pages rendering richText editors include the trix script tag`() {
        assertTrue(templatesDir.isDirectory, "Templates directory not found at $templatesDir")

        // Pages that host richText editor inputs:
        val expectedTrixPages = listOf(
            "materials/form.html",
            "materials/view.html",
            "dance-figures/form.html",
            "choreographies/form.html",
            "training-events/form.html"
        )

        for (pageRelPath in expectedTrixPages) {
            val file = File(templatesDir, pageRelPath)
            assertTrue(file.isFile, "Template not found: $file")
            val content = file.readText()
            assertTrue(
                content.contains("trix@") && content.contains("<script"),
                "$pageRelPath hosts richText inputs but is missing the <script src=\".../trix.umd.min.js\"> tag."
            )
        }
    }
}
