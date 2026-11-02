package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Text size setting (#454): type must be sized in rem so the browser default font size
 * and the in-app `data-text-size` root scale reach it. A px font size would stay fixed.
 */
class TextSizeGuardTest {

    private val inputCss = File("src/main/resources/frontend/input.css").readText()
    private val templates = File("src/main/resources/templates")
    private val staticJs = File("src/main/resources/static/js")

    @Test
    fun `input css declares no px font sizes or px typography tokens`() {
        val offenders = Regex("""(font-size|line-height|--text-[\w-]+(?:--line-height)?)\s*:\s*\d+px""")
            .findAll(inputCss).map { it.value }.toList()
        assertTrue(offenders.isEmpty(), "px typography found in input.css: $offenders")
    }

    @Test
    fun `templates and scripts declare no arbitrary px text sizes`() {
        val pattern = Regex("""text-\[\d+(?:\.\d+)?px]""")
        val offenders = (templates.walkTopDown() + staticJs.walkTopDown())
            .filter { it.isFile && (it.extension == "html" || it.extension == "js") }
            .flatMap { f -> pattern.findAll(f.readText()).map { "${f.name}: ${it.value}" } }
            .toList()
        assertTrue(offenders.isEmpty(), "px text sizes found: $offenders")
    }

    @Test
    fun `stylesheet scales the root font size for large and extra large`() {
        assertTrue(inputCss.contains("html[data-text-size='large']"))
        assertTrue(inputCss.contains("html[data-text-size='extra_large']"))
    }
}
