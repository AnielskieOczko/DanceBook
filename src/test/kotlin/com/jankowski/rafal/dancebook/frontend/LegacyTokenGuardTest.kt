package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Guards against the regression or reintroduction of retired legacy colour aliases
 * and the custom alphabetic spacing scale (Issue #319, Part of #46 phase 13).
 *
 * Retired colour aliases:
 * - `text-text-primary` (retired in favour of `text-on-surface`)
 * - `text-text-secondary` (retired in favour of `text-on-surface-variant`)
 * - `--color-text-primary`, `--color-text-secondary`
 *
 * Retired alphabetic spacing scale:
 * - `xs` (4px -> 1)
 * - `base` (8px -> 2)
 * - `sm` (12px -> 3)
 * - `md` (24px -> 6)
 * - `gutter` (32px -> 8)
 * - `margin` (40px -> 10)
 * - `lg` (48px -> 12)
 * - `xl` (80px -> 20)
 *
 * These custom spacing tokens previously shadowed Tailwind's stock container sizes
 * (causing `max-w-md` to resolve to 24px instead of 28rem).
 */
class LegacyTokenGuardTest {

    private val templatesDir = File("src/main/resources/templates")
    private val staticJsDir = File("src/main/resources/static/js")
    private val inputCssFile = File("src/main/resources/frontend/input.css")

    companion object {
        val COLOR_ALIAS_PATTERN = Regex("""\b(?:[a-zA-Z0-9_-]+:)?(?:text|bg|border|ring|divide)-(?:text-primary|text-secondary)\b""")
        val SPACING_UTILITY_PATTERN = Regex(
            """\b(?:[a-zA-Z0-9_-]+:)?-?(?:p|px|py|pt|pb|pl|pr|ps|pe|m|mx|my|mt|mb|ml|mr|ms|me|gap|gap-x|gap-y|space-x|space-y|top|bottom|left|right|inset|inset-x|inset-y)-(?:xs|sm|md|lg|xl|base|gutter|margin)\b"""
        )
        val RETIRED_CSS_TOKENS = listOf(
            "--color-text-primary",
            "--color-text-secondary",
            "--spacing-base",
            "--spacing-xs",
            "--spacing-sm",
            "--spacing-md",
            "--spacing-lg",
            "--spacing-xl",
            "--spacing-gutter",
            "--spacing-margin"
        )
    }

    @Test
    fun `no template or script references retired colour aliases`() {
        assertTrue(templatesDir.isDirectory, "Templates directory not found: $templatesDir")
        assertTrue(staticJsDir.isDirectory, "Static JS directory not found: $staticJsDir")

        val violations = mutableListOf<String>()

        fun scanDirectory(dir: File, extension: String) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension == extension }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            COLOR_ALIAS_PATTERN.findAll(line).forEach { match ->
                                violations.add("${file.path}:${index + 1}: found '${match.value}' in line: ${line.trim()}")
                            }
                        }
                    }
                }
        }

        scanDirectory(templatesDir, "html")
        scanDirectory(staticJsDir, "js")

        assertTrue(
            violations.isEmpty(),
            "Found retired legacy colour aliases in templates/scripts:\n" + violations.joinToString("\n")
        )
    }

    @Test
    fun `no template, script or stylesheet references alphabetic spacing utilities`() {
        assertTrue(templatesDir.isDirectory, "Templates directory not found: $templatesDir")
        assertTrue(staticJsDir.isDirectory, "Static JS directory not found: $staticJsDir")
        assertTrue(inputCssFile.isFile, "input.css not found: $inputCssFile")

        val violations = mutableListOf<String>()

        fun scanFile(file: File) {
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    // Skip comment-only lines in input.css
                    val trimmed = line.trim()
                    if (trimmed.startsWith("/*") || trimmed.startsWith("*")) return@forEachIndexed

                    SPACING_UTILITY_PATTERN.findAll(line).forEach { match ->
                        violations.add("${file.path}:${index + 1}: found '${match.value}' in line: ${trimmed}")
                    }
                }
            }
        }

        templatesDir.walkTopDown().filter { it.isFile && it.extension == "html" }.forEach { scanFile(it) }
        staticJsDir.walkTopDown().filter { it.isFile && it.extension == "js" }.forEach { scanFile(it) }
        scanFile(inputCssFile)

        assertTrue(
            violations.isEmpty(),
            "Found retired alphabetic spacing utilities in templates/scripts/stylesheets:\n" + violations.joinToString("\n")
        )
    }

    @Test
    fun `input css does not define retired colour aliases or alphabetic spacing scale`() {
        assertTrue(inputCssFile.isFile, "input.css not found: $inputCssFile")
        val content = inputCssFile.readText()

        val violations = mutableListOf<String>()
        for (token in RETIRED_CSS_TOKENS) {
            if (content.contains(token)) {
                violations.add("input.css still defines retired token: $token")
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Found retired design tokens defined in input.css:\n" + violations.joinToString("\n")
        )
    }

    @ParameterizedTest(name = "guard regex catches retired token {0}")
    @ValueSource(
        strings = [
            "text-text-primary",
            "text-text-secondary",
            "hover:text-text-primary",
            "bg-text-primary",
            "border-text-secondary",
            "space-y-gutter",
            "gap-gutter",
            "p-md",
            "md:p-margin",
            "py-lg",
            "md:py-xl",
            "space-y-xl",
            "space-y-lg",
            "m-base",
            "mt-xs",
            "-mt-sm",
            "px-gutter"
        ]
    )
    fun `guard patterns catch all retired aliases and spacing names`(sampleClass: String) {
        val matchesColor = COLOR_ALIAS_PATTERN.containsMatchIn(sampleClass)
        val matchesSpacing = SPACING_UTILITY_PATTERN.containsMatchIn(sampleClass)
        assertTrue(
            matchesColor || matchesSpacing,
            "Guard patterns must detect '$sampleClass' as a violation if reintroduced."
        )
    }

    @ParameterizedTest(name = "guard regex permits valid token {0}")
    @ValueSource(
        strings = [
            "text-on-surface",
            "text-on-surface-variant",
            "text-primary",
            "text-secondary",
            "text-xs",
            "text-sm",
            "text-base",
            "text-lg",
            "text-xl",
            "rounded-sm",
            "rounded-md",
            "rounded-lg",
            "rounded-xl",
            "shadow-xs",
            "shadow-sm",
            "shadow-md",
            "btn-sm",
            "btn-md",
            "max-w-md",
            "max-w-sm",
            "max-w-lg",
            "space-y-8",
            "space-y-12",
            "space-y-20",
            "gap-8",
            "p-6",
            "md:p-10",
            "py-12",
            "md:py-20"
        ]
    )
    fun `guard patterns do not false-positive on valid classes`(validClass: String) {
        assertFalse(
            COLOR_ALIAS_PATTERN.containsMatchIn(validClass),
            "Guard pattern must not flag valid class '$validClass' as color alias violation."
        )
        assertFalse(
            SPACING_UTILITY_PATTERN.containsMatchIn(validClass),
            "Guard pattern must not flag valid class '$validClass' as spacing utility violation."
        )
    }

    @Test
    fun `max-w-md container utility resolves to stock 28rem in output css`() {
        val outputCssFile = File("src/main/resources/static/css/output.css")
        if (outputCssFile.isFile) {
            val css = outputCssFile.readText()
            assertTrue(
                css.contains("--container-md:28rem"),
                "Expected output.css to define --container-md:28rem"
            )
            assertTrue(
                css.contains(".max-w-md{max-width:var(--container-md)}"),
                "Expected .max-w-md to use var(--container-md)"
            )
        }
    }
}
