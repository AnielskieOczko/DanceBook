package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Guards against regressions in the global go-to-top / go-to-bottom control (Issue #224).
 *
 * Requirements:
 * - Floating vertical pill at bottom-right in the shared layout (`layout.html`).
 * - Two buttons (top and bottom) with localized labels (en and pl) and 3px visible focus ring matching mock.
 * - Sits above mobile navigation and assistant launcher; clear on desktop.
 * - Hidden by default and on short pages without relying on a utility class that unlayered CSS would defeat.
 * - Disappears when dialogs are open (`body:has(dialog[open])`).
 * - Skips smooth scrolling when user prefers reduced motion.
 * - Dynamic appearance on pages taller than about two screens; re-evaluates on HTMX swaps.
 */
class ScrollToControlsGuardTest {

    private val layoutFile = File("src/main/resources/templates/layout.html")
    private val inputCssFile = File("src/main/resources/frontend/input.css")
    private val mainJsFile = File("src/main/resources/static/js/main.js")
    private val messagesEnFile = File("src/main/resources/messages.properties")
    private val messagesPlFile = File("src/main/resources/messages_pl.properties")

    @Test
    fun `layout html contains scroll-to-controls container with proper buttons and fragments`() {
        assertTrue(layoutFile.isFile, "layout.html must exist")
        val content = layoutFile.readText()
        val doc = Jsoup.parse(content)

        val nav = doc.selectFirst("#scrollToControls")
        assertNotNull(nav, "#scrollToControls must exist in layout.html")
        assertEquals("group", nav!!.attr("role"), "#scrollToControls must have role='group'")
        assertTrue(nav.hasClass("goto"), "#scrollToControls must have class 'goto'")
        assertTrue(nav.hasAttr("hidden"), "#scrollToControls must initially have hidden attribute")
        assertFalse(nav.hasClass("hidden"), "#scrollToControls must not rely on Tailwind hidden utility class which unlayered CSS defeats")
        assertTrue(nav.hasAttr("th:attr"), "#scrollToControls must have th:attr for aria-label")
        assertTrue(nav.attr("th:attr").contains("nav.scroll.group"), "th:attr must reference nav.scroll.group")

        val topBtn = nav.selectFirst("#scrollToTopBtn")
        assertNotNull(topBtn, "#scrollToTopBtn must exist")
        assertEquals("button", topBtn!!.attr("type"), "#scrollToTopBtn must have type='button'")
        assertTrue(topBtn.hasAttr("disabled"), "#scrollToTopBtn must be disabled initially")
        assertTrue(topBtn.hasAttr("th:attr"), "#scrollToTopBtn must localize aria-label and title")
        assertTrue(topBtn.attr("th:attr").contains("nav.scroll.top"), "#scrollToTopBtn must reference nav.scroll.top")

        val bottomBtn = nav.selectFirst("#scrollToBottomBtn")
        assertNotNull(bottomBtn, "#scrollToBottomBtn must exist")
        assertEquals("button", bottomBtn!!.attr("type"), "#scrollToBottomBtn must have type='button'")
        assertTrue(bottomBtn.hasAttr("disabled"), "#scrollToBottomBtn must be disabled initially")
        assertTrue(bottomBtn.hasAttr("th:attr"), "#scrollToBottomBtn must localize aria-label and title")
        assertTrue(bottomBtn.attr("th:attr").contains("nav.scroll.bottom"), "#scrollToBottomBtn must reference nav.scroll.bottom")

        // Icon inclusions
        assertTrue(content.contains("keyboard_arrow_up"), "Top button must include keyboard_arrow_up icon")
        assertTrue(content.contains("keyboard_arrow_down"), "Bottom button must include keyboard_arrow_down icon")
        assertTrue(content.contains("title=null"), "Icon inclusion must pass title=null to avoid inheriting outer title")
    }

    @Test
    fun `scroll control labels are localized in both English and Polish bundles`() {
        val enProps = Properties().apply { messagesEnFile.reader().use { load(it) } }
        val plProps = Properties().apply { messagesPlFile.reader().use { load(it) } }

        val keys = listOf("nav.scroll.group", "nav.scroll.top", "nav.scroll.bottom")
        for (key in keys) {
            val enVal = enProps.getProperty(key)
            val plVal = plProps.getProperty(key)

            assertNotNull(enVal, "Missing English message key: $key")
            assertNotNull(plVal, "Missing Polish message key: $key")
            assertTrue(enVal!!.isNotBlank(), "English message key must not be blank: $key")
            assertTrue(plVal!!.isNotBlank(), "Polish message key must not be blank: $key")
        }
    }

    @Test
    fun `input css defines goto styling with responsive positioning and dialog concealment`() {
        assertTrue(inputCssFile.isFile, "input.css must exist")
        val css = inputCssFile.readText()

        assertTrue(css.contains(".goto {"), "input.css must define .goto selector")
        assertTrue(css.contains("var(--color-surface)"), ".goto must use surface color token")
        assertTrue(css.contains("var(--color-outline-variant)"), ".goto must use outline-variant token")
        assertTrue(css.contains("var(--radius-pill)"), ".goto must use radius-pill token")
        assertTrue(css.contains("var(--shadow-ambient)"), ".goto must use shadow-ambient token")

        // Hiding mechanism must not be defeated by unlayered display: flex (#224 review)
        assertTrue(
            css.contains(Regex("""\.goto\s*\{[^}]*display:\s*none;""")),
            ".goto default rule must set display: none so it starts hidden and cannot defeat hidden states"
        )
        assertFalse(
            css.contains(Regex("""\.goto\s*\{[^}]*display:\s*flex;""")),
            ".goto default rule must not set display: flex unconditionally; display: flex belongs in .goto.is-visible"
        )
        assertTrue(
            css.contains(".goto.is-visible {"),
            ".goto.is-visible must be defined"
        )
        assertTrue(
            css.contains(".goto[hidden] {"),
            ".goto[hidden] rule must be defined"
        )

        // Focus ring matches mock: 3px primary
        assertTrue(
            css.contains("box-shadow: 0 0 0 3px var(--color-primary);"),
            ".goto button:focus-visible must use 3px primary ring to match mock"
        )

        // Clearance above mobile navigation and assistant bar
        assertTrue(css.contains("84px"), ".goto must clear mobile nav (84px bottom)")
        assertTrue(css.contains("env(safe-area-inset-bottom"), ".goto must account for safe-area-inset-bottom")
        assertTrue(css.contains("140px"), ".goto must clear mobile assistant bar when visible (140px)")

        // Desktop positioning
        assertTrue(css.contains("28px"), ".goto must use 28px bottom/right positioning on desktop")

        // Dialog concealment
        assertTrue(
            css.contains("body:has(dialog[open]) .goto"),
            "input.css must conceal .goto when a dialog is open"
        )

        // Reduced motion
        assertTrue(css.contains("prefers-reduced-motion"), "input.css must respect prefers-reduced-motion")
    }

    @Test
    fun `main js implements scroll controls logic without document body listeners`() {
        assertTrue(mainJsFile.isFile, "main.js must exist")
        val js = mainJsFile.readText()

        assertFalse(
            js.contains("document.body.addEventListener"),
            "main.js must never bind listeners to document.body"
        )

        assertTrue(js.contains("initScrollToControls"), "main.js must define initScrollToControls")
        assertTrue(js.contains("scrollToControls"), "main.js must query #scrollToControls")
        assertTrue(js.contains("scrollToTopBtn"), "main.js must query #scrollToTopBtn")
        assertTrue(js.contains("scrollToBottomBtn"), "main.js must query #scrollToBottomBtn")

        // Visibility toggling via owned class and hidden attribute, avoiding utility class defeat
        assertTrue(
            js.contains("controls.classList.add('is-visible')"),
            "main.js must add is-visible class when page is long"
        )
        assertTrue(
            js.contains("controls.classList.remove('is-visible')"),
            "main.js must remove is-visible class when page is short"
        )
        assertTrue(
            js.contains("controls.removeAttribute('hidden')"),
            "main.js must remove hidden attribute when page is long"
        )
        assertTrue(
            js.contains("controls.setAttribute('hidden', '')"),
            "main.js must set hidden attribute when page is short"
        )

        // 2-screen threshold
        assertTrue(
            js.contains("clientHeight * 2"),
            "main.js must use the 2-screen height threshold rule"
        )

        // Smooth scroll and reduced motion check
        assertTrue(
            js.contains("prefers-reduced-motion"),
            "main.js must check prefers-reduced-motion media query"
        )
        assertTrue(
            js.contains("window.scrollTo"),
            "main.js must call window.scrollTo"
        )

        // HTMX hooks
        assertTrue(js.contains("htmx:afterSwap"), "main.js must update scroll controls on htmx:afterSwap")
        assertTrue(js.contains("htmx:afterSettle"), "main.js must update scroll controls on htmx:afterSettle")
    }
}
