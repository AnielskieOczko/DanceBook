package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against inline event handlers (`onclick=`, `onchange=`, `onsubmit=`, `onerror=`, etc.)
 * in Thymeleaf templates (#323).
 *
 * Why this breaks:
 * The Content Security Policy (`script-src 'self' https://unpkg.com`) does not include `'unsafe-inline'`.
 * The browser silently ignores inline event handler attributes, meaning controls appear interactive
 * but execute nothing.
 *
 * The correct patterns are:
 * - Delete confirmations: use `data-confirm="..."` or `th:data-confirm="#{...}"` handled by `main.js`.
 * - Auto-submitting inputs: use `class="... js-auto-submit"` or `class="js-filter-select"` handled by `main.js`.
 * - Image fallbacks: use `data-fallback="..."` handled by `main.js`.
 * - Other dynamic behaviour: bind event listeners in `static/js`.
 */
class InlineEventHandlerGuardTest {

    private val templateRoot = File("src/main/resources/templates")

    private val openingTagRegex = Regex("""<([a-zA-Z0-9:-]+)((?:'[^']*'|"[^"]*"|[^>'"])*)>""", RegexOption.DOT_MATCHES_ALL)
    private val attributeRegex = Regex("""\b([a-zA-Z0-9_:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")
    private val inlineHandlerNameRegex = Regex("""^(?:th:)?on[a-zA-Z]+$""", RegexOption.IGNORE_CASE)
    private val thAttrInlineHandlerRegex = Regex("""\bon[a-zA-Z]+\s*=""", RegexOption.IGNORE_CASE)

    data class Offense(val file: String, val line: Int, val snippet: String) {
        override fun toString(): String = "$file:$line  $snippet"
    }

    fun findInlineEventHandlers(content: String, fileName: String = "template.html"): List<Offense> {
        val offenses = mutableListOf<Offense>()
        val lines = content.lines()

        for (tagMatch in openingTagRegex.findAll(content)) {
            val tagName = tagMatch.groupValues[1]
            val attrsString = tagMatch.groupValues[2]
            val attrsStartInContent = tagMatch.range.first + 1 + tagName.length

            for (attrMatch in attributeRegex.findAll(attrsString)) {
                val attrName = attrMatch.groupValues[1]
                val attrOffsetInContent = attrsStartInContent + attrMatch.range.first

                val isOffense = if (inlineHandlerNameRegex.matches(attrName)) {
                    true
                } else if (attrName.equals("th:attr", ignoreCase = true)) {
                    val attrValue = attrMatch.groupValues[2].ifEmpty { attrMatch.groupValues[3] }
                    thAttrInlineHandlerRegex.containsMatchIn(attrValue)
                } else {
                    false
                }

                if (isOffense) {
                    val lineNumber = content.substring(0, attrOffsetInContent).count { it == '\n' } + 1
                    val snippet = lines.getOrElse(lineNumber - 1) { "" }.trim()
                    offenses.add(Offense(fileName, lineNumber, snippet))
                }
            }
        }
        return offenses
    }

    @Test
    fun `no template contains an inline event-handler attribute`() {
        assertTrue(templateRoot.isDirectory, "Templates directory not found at $templateRoot")

        val offenders = templateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .flatMap { file ->
                val relativePath = file.relativeTo(templateRoot).path.replace('\\', '/')
                findInlineEventHandlers(file.readText(), relativePath)
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Found templates containing inline event handler attributes (blocked by CSP script-src 'self'):\n\n" +
                "Why this breaks:\n" +
                "The Content Security Policy allows scripts only from 'self' and unpkg.com, without 'unsafe-inline'.\n" +
                "The browser silently ignores inline handlers (onclick, onchange, onsubmit, onerror, etc.), so the\n" +
                "element looks active but its action never runs.\n\n" +
                "Fix:\n" +
                "- Delete confirm: use data-confirm=\"...\" and th:data-confirm=\"#{key}\"\n" +
                "- Form auto-submit on change: add class=\"js-auto-submit\"\n" +
                "- Image error fallback: use data-fallback=\"...\"\n" +
                "- Custom behavior: bind event listener in static/js\n\n" +
                "Offenders:\n" +
                offenders.joinToString("\n") { "  $it" }
        )
    }

    @Test
    fun `guard test fails when an inline event-handler is present in a template string`() {
        val badTemplates = listOf(
            """<button onclick="doSomething()">Click</button>""" to "onclick",
            """<input type="radio" onchange="this.form.submit()">""" to "onchange",
            """<form onsubmit="return confirm('Delete?')"><button>Delete</button></form>""" to "onsubmit",
            """<img src="/img.png" onerror="this.src='/fallback.png'">""" to "onerror",
            """<button th:onclick="'doSomething()'">Click</button>""" to "th:onclick",
            """<div th:attr="data-confirm=#{msg}, onsubmit='return false'"></div>""" to "onsubmit"
        )

        for ((badHtml, expectedHandler) in badTemplates) {
            val offenses = findInlineEventHandlers(badHtml, "bad.html")
            assertFalse(
                offenses.isEmpty(),
                "Expected detector to catch '$expectedHandler' in template:\n$badHtml"
            )
            assertEquals("bad.html", offenses.first().file)
            assertEquals(1, offenses.first().line)
        }

        // Multiline tag: verify accurate line number reporting
        val multilineHtml = """
            <div class="container">
                <button
                    type="button"
                    onclick="handleClick()">
                    Submit
                </button>
            </div>
        """.trimIndent()

        val multilineOffenses = findInlineEventHandlers(multilineHtml, "multiline.html")
        assertEquals(1, multilineOffenses.size)
        assertEquals(4, multilineOffenses.first().line)
        assertTrue(multilineOffenses.first().snippet.contains("onclick=\"handleClick()\""))
    }

    @Test
    fun `guard test does not trigger on valid attributes or text containing on`() {
        val validHtml = """
            <div class="text-on-surface bg-surface font-medium">
                <button type="submit" data-confirm="Are you sure?" th:data-confirm="#{confirm.key}">
                    Delete
                </button>
                <input type="radio" class="accent-primary js-auto-submit" name="option">
                <img th:src="@{/img.png}" data-fallback="/placeholder.png" alt="Based on=description">
            </div>
        """.trimIndent()

        val offenses = findInlineEventHandlers(validHtml, "valid.html")
        assertTrue(
            offenses.isEmpty(),
            "Expected 0 offenses for valid HTML, but got: $offenses"
        )
    }
}
