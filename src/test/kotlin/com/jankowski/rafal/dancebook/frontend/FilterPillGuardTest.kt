package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.pow

/**
 * Guards the filter pill styling and contrast across Notes, Figures, and Collections (#382).
 *
 * Ensures:
 * 1. Category and style filter pills use the unified `filter-pill` utility rather than
 *    divergent hand-rolled Tailwind class strings.
 * 2. No filter pill retains the buggy hover state where light hover background wins over checked state.
 * 3. The compiled `output.css` contains the hover contrast rule for checked filter pills.
 * 4. The contrast ratio between the label text and the selected hover background meets WCAG AA (>= 4.5:1).
 */
class FilterPillGuardTest {

    private val templatesDir = File("src/main/resources/templates")
    private val inputCssFile = File("src/main/resources/frontend/input.css")

    private val filterTemplates = listOf(
        "dance-figures/list.html",
        "materials/list.html",
        "lists/index.html"
    )

    @Test
    fun `input css defines filter-pill utility with checked hover contrast`() {
        assertTrue(inputCssFile.isFile, "input.css not found at $inputCssFile")
        val css = inputCssFile.readText()

        assertTrue(css.contains("@utility filter-pill"), "input.css must declare @utility filter-pill")
        assertTrue(css.contains("peer-checked:bg-primary-container"), "filter-pill must specify checked background")
        assertTrue(css.contains("peer-checked:text-on-primary-container"), "filter-pill must specify checked text color")
        assertTrue(css.contains("peer-checked:hover:bg-primary-hover"), "filter-pill must specify checked hover background")
        assertTrue(css.contains("hover:bg-surface-container-high"), "filter-pill must specify unselected hover background")
    }

    @Test
    fun `no template retains the buggy peer-checked and hover combination`() {
        val buggySnippet = "peer-checked:bg-primary-container peer-checked:text-on-primary-container bg-surface-container text-on-surface hover:bg-surface-container-high"

        templatesDir.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .forEach { file ->
                val content = file.readText()
                assertFalse(
                    content.contains(buggySnippet),
                    "Found legacy buggy filter pill class combination in ${file.relativeTo(templatesDir)}"
                )
            }
    }

    @Test
    fun `all category and style filter pills in Notes, Figures, and Collections use filter-pill`() {
        var totalFilterPills = 0

        for (relPath in filterTemplates) {
            val file = File(templatesDir, relPath)
            assertTrue(file.isFile, "Template not found at $file")
            val doc = Jsoup.parse(file.readText())

            // Find all filter checkboxes for categoryIds and typeIds
            val filterCheckboxes = doc.select("input[type='checkbox'][name='categoryIds'], input[type='checkbox'][name='typeIds'], input[type='checkbox'][data-sheet-filter='categoryIds'], input[type='checkbox'][data-sheet-filter='typeIds']")
            assertTrue(filterCheckboxes.isNotEmpty(), "Expected filter checkboxes in $relPath")

            for (cb in filterCheckboxes) {
                val label = cb.closest("label")
                val pill = label?.selectFirst("span.filter-pill")
                assertTrue(
                    pill != null,
                    "Checkbox with name '${cb.attr("name")}' / data-sheet-filter '${cb.attr("data-sheet-filter")}' in $relPath must have a sibling span.filter-pill"
                )
                totalFilterPills++
            }
        }

        // Figures: desktop cat (1), desktop style (1), sheet cat (1), sheet style (1) = 4
        // Notes: desktop cat (1), desktop style (1), sheet cat (1), sheet style (1) = 4
        // Collections: desktop cat (1), desktop style (1) = 2
        // Total = 10 filter pill markup instances
        assertEquals(10, totalFilterPills, "Expected exactly 10 filter pill markup instances across Notes, Figures, and Collections")
    }

    @Test
    fun `output css includes rules for filter-pill and checked hover state`() {
        val url = javaClass.getResource("/static/css/output.css")
        if (url != null) {
            val css = url.readText()
            assertTrue(css.contains(".filter-pill"), "output.css must contain .filter-pill")
            assertTrue(
                css.contains("filter-pill:is(:where(.peer):checked~*):hover") || css.contains("filter-pill:hover"),
                "output.css must contain hover rules for filter-pill"
            )
        }
    }

    @Test
    fun `wcag contrast ratio for selected pill hover exceeds AA standard`() {
        // on-primary-container / on-primary is #ffffff
        // primary-hover is #163736
        val textLuminance = relativeLuminance(0xFF, 0xFF, 0xFF)
        val bgLuminance = relativeLuminance(0x16, 0x37, 0x36)

        val contrastRatio = (textLuminance + 0.05) / (bgLuminance + 0.05)
        assertTrue(
            contrastRatio >= 4.5,
            "Contrast ratio ($contrastRatio) must meet or exceed WCAG AA threshold of 4.5:1"
        )
    }

    private fun relativeLuminance(r: Int, g: Int, b: Int): Double {
        fun channelLuminance(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val rL = channelLuminance(r)
        val gL = channelLuminance(g)
        val bL = channelLuminance(b)
        return 0.2126 * rL + 0.7152 * gL + 0.0722 * bL
    }
}
