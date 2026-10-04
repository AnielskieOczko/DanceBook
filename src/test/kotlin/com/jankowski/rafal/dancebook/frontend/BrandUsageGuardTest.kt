package com.jankowski.rafal.dancebook.frontend

import com.jankowski.rafal.dancebook.config.Brand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against regression of brand name usage in Thymeleaf templates.
 *
 * The application is named DanceBook, while "choreography" is a domain feature.
 * The brand name must not regress to legacy "CHOREO" anywhere in templates.
 */
class BrandUsageGuardTest {

    private val templatesDir = File("src/main/resources/templates")

    @Test
    fun `brand constant has expected value DanceBook`() {
        assertEquals("DanceBook", Brand.NAME)
    }

    @Test
    fun `no template uses CHOREO as brand name`() {
        assertTrue(templatesDir.isDirectory, "Templates directory not found at $templatesDir")

        val violations = mutableListOf<String>()
        val exactBrandPattern = Regex("""\bCHOREO\b""")

        templatesDir.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (exactBrandPattern.containsMatchIn(line)) {
                            violations.add("${file.relativeTo(templatesDir).path}:${index + 1}: $line")
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Found legacy CHOREO brand usage in templates:\n" + violations.joinToString("\n")
        )
    }

    @Test
    fun `layout template uses brand constant and defaults to DanceBook`() {
        val layoutFile = File(templatesDir, "layout.html")
        assertTrue(layoutFile.exists(), "layout.html not found")

        val content = layoutFile.readText()
        assertTrue(
            content.contains("brandName ?: 'DanceBook'"),
            "layout.html must bind title and header to brandName with DanceBook default"
        )
        assertTrue(
            content.contains(">DanceBook</title>"),
            "layout.html must have DanceBook as default title text"
        )
        assertTrue(
            content.contains(">DanceBook</h1>"),
            "layout.html must have DanceBook as default header text"
        )
    }
}
