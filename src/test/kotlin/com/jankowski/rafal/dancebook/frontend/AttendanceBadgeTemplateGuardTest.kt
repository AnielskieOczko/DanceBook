package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against regression of attendance badge consolidation (Issue #129, Item 12 of #46).
 *
 * No template outside the designated badge fragment (`fragments/badge.html`) is permitted
 * to map an attendance status, a training outcome, or an unconfirmed/awaiting-confirmation state
 * to a badge CSS class or badge variant.
 *
 * All screens rendering session attendance or training outcomes must delegate directly to
 * `fragments/badge :: attendanceBadge(status=..., unconfirmed=...)`.
 *
 * What this test catches:
 * 1. Opening HTML tag attributes (matching across lines) combining attendance indicators
 *    (`attendanceStatus`, `record.outcome`, `row.outcome`, `AttendanceStatus`, `TrainingOutcome`,
 *    `isAwaitingConfirmation`) with badge classes (`badge-success`, `badge-danger`, `badge-warning`,
 *    `badge-neutral`, `badge-accent`, `badge-primary`) or badge variants.
 * 2. Multi-line ternary expressions evaluating status literals (`ATTENDED`, `SKIPPED`, `CANCELLED`,
 *    `PLANNED`) to select badge classes (such as the multi-line ternary formerly in `list.html`).
 * 3. Conditional badge tags (`th:if` / `th:unless` checking `isAwaitingConfirmation` or status)
 *    that manually style unconfirmed badges using badge classes instead of `attendanceBadge`.
 *
 * What this test does NOT catch:
 * 1. Form input controls and dropdown options (`<select name="attendanceStatuses">`,
 *    `path='attendanceStatus'`, `attendanceStatusOptions`) that bind inputs for editing rather than display.
 * 2. Logic outside Thymeleaf templates: server-side Kotlin palette derivation (e.g. `TrainingEventPalette`)
 *    and client-side JavaScript canvas renderers (e.g. `training-stats.js` Chart.js colors,
 *    `training-calendar.js` FullCalendar chip styles).
 * 3. Complex multi-element indirection where an outer enclosing tag sets a generic boolean/variable
 *    without status keywords in the inner element's attribute block.
 */
class AttendanceBadgeTemplateGuardTest {

    private val templateRoot = File("src/main/resources/templates")
    private val permittedFragmentRelativePath = "fragments/badge.html"

    private val openingTagRegex = Regex("""<([a-zA-Z0-9:-]+)((?:'[^']*'|"[^"]*"|[^>'"])*)>""", RegexOption.DOT_MATCHES_ALL)
    private val badgeClassPattern = Regex("""badge-(?:primary|secondary|accent|neutral|danger|success|warning)\b""")
    private val statusKeywords = Regex("""\b(?:attendanceStatus|isAwaitingConfirmation|TrainingOutcome|AttendanceStatus)\b""")
    private val statusLiteralCheck = Regex("""\b(?:ATTENDED|SKIPPED|CANCELLED|PLANNED)\b""")
    private val recordOutcomeCheck = Regex("""\b(?:record|row)\.outcome\b""")

    @Test
    fun `no template outside badge fragment maps attendance status or outcome to badge variant`() {
        assertTrue(templateRoot.isDirectory, "Templates directory not found at $templateRoot")

        val permittedFile = File(templateRoot, permittedFragmentRelativePath)
        assertTrue(permittedFile.isFile, "Permitted badge fragment not found at $permittedFile")

        // Guard against tautology: the permitted badge fragment must actually contain attendance mapping.
        val permittedContent = permittedFile.readText()
        assertTrue(
            permittedContent.contains("attendanceBadge") && badgeClassPattern.containsMatchIn(permittedContent),
            "$permittedFragmentRelativePath must define attendanceBadge with badge classes."
        )

        val offenders = templateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .filter { it.relativeTo(templateRoot).path.replace('\\', '/') != permittedFragmentRelativePath }
            .flatMap { file ->
                val content = file.readText()
                openingTagRegex.findAll(content)
                    .filter { match ->
                        val attrs = match.groupValues[2]
                        if (!badgeClassPattern.containsMatchIn(attrs)) return@filter false

                        val hasStatusKeyword = statusKeywords.containsMatchIn(attrs)
                        val hasRecordOutcome = recordOutcomeCheck.containsMatchIn(attrs)
                        val hasStatusLiteralInExpression = statusLiteralCheck.containsMatchIn(attrs) &&
                            (attrs.contains("?") || attrs.contains("th:class") || attrs.contains("th:classappend"))

                        hasStatusKeyword || hasRecordOutcome || hasStatusLiteralInExpression
                    }
                    .map { match ->
                        val lineNumber = content.substring(0, match.range.first).count { it == '\n' } + 1
                        val firstLine = match.value.lines().first().trim()
                        "${file.relativeTo(templateRoot)}:$lineNumber  $firstLine"
                    }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Found attendance status or outcome mapped to badge classes outside $permittedFragmentRelativePath. " +
                "Item 12 of #46 consolidates all attendance state display into fragments/badge :: attendanceBadge(status=..., unconfirmed=...). " +
                "Do not hand-roll badge styling or map attendance statuses/outcomes to badge-* classes in template attributes. " +
                "Offenders:\n" +
                offenders.joinToString("\n") { "  $it" }
        )
    }
}
