package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against regression of pinned table column row-state tracking (Issue #131).
 *
 * In CSS table rendering, cells paint after rows (Layer 6 over Layer 5).
 * If a pinned cell sets an explicit, static background (e.g. `bg-surface`), it paints over and
 * conceals whatever background the row computes (e.g. hover highlight, danger tints for orphaned items).
 *
 * The architecture solves this generically without enumerating row states or naming template classes:
 * 1. The row (`.table-row`) defines an opaque base background (`var(--color-surface)`).
 * 2. The pinned cell (`.table-cell-pinned`) inherits its background (`background-color: inherit`).
 *
 * This guarantees:
 * - Default state: cell inherits `var(--color-surface)` from `.table-row`, providing 100% opacity
 *   against horizontally scrolling columns passing underneath.
 * - Dynamic row states: cell automatically tracks whatever background its row computes (e.g.
 *   `hover:bg-surface-container/50`, `bg-danger-soft/50`, or any future row state utility),
 *   with zero descendant rules or escaped template selectors in the stylesheet.
 *
 * What this guard test catches:
 * 1. Pinned cell setting an independent static background color (e.g. `var(--color-surface)` directly
 *    on `.table-cell-pinned`), which causes cell backgrounds to cover row highlights and tints.
 * 2. Pinned cell using `transparent` instead of `inherit`, which breaks horizontal scroll occlusion
 *    on default rows.
 * 3. Table row (`.table-row`) omitting an opaque base background (`var(--color-surface)`), which
 *    would cause `inherit` to resolve to transparent and allow scrolled content to bleed through.
 * 4. The stylesheet attempting to reintroduce hardcoded template utility selectors (e.g. `bg-danger-soft`)
 *    or duplicate custom-property mechanisms instead of clean inheritance.
 * 5. Admin templates failing to apply `table-row` to rows or omitting row-level state tints
 *    (e.g. orphaned danger highlight on Google Drive Files).
 */
class PinnedColumnRowStateGuardTest {

    private val cssFile = File("src/main/resources/static/css/output.css")
    private val css by lazy {
        assertTrue(cssFile.isFile, "output.css not found at ${cssFile.absolutePath}")
        cssFile.readText()
    }

    private val templateFile = File("src/main/resources/templates/admin/dashboard.html")
    private val doc by lazy {
        assertTrue(templateFile.isFile, "Template file not found at ${templateFile.absolutePath}")
        Jsoup.parse(templateFile.readText(), "", Parser.xmlParser())
    }

    @Test
    fun `pinned cell inherits background from row rather than defining a static color`() {
        val cellBlockRegex = Regex("""\.table-cell-pinned\s*\{([^}]*)\}""")
        val match = cellBlockRegex.find(css)
        assertTrue(match != null, "output.css must contain .table-cell-pinned rule")
        val cellBlock = match!!.groupValues[1]

        // Must inherit background-color from its parent row
        assertTrue(
            cellBlock.contains("background-color:inherit"),
            ".table-cell-pinned must inherit its background-color from the parent row so it dynamically tracks all row states."
        )

        // Must NOT set a static background-color directly on the cell
        assertFalse(
            cellBlock.contains("var(--color-surface)"),
            ".table-cell-pinned must not set static var(--color-surface) directly; that paints over row hover/danger states."
        )

        // Must NOT be transparent
        assertFalse(
            cellBlock.contains("background-color:transparent"),
            ".table-cell-pinned must not be transparent."
        )
    }

    @Test
    fun `table row provides opaque base background for pinned cell inheritance`() {
        val rowBgRegex = Regex("""\.table-row\s*\{[^}]*background-color\s*:\s*var\(--color-surface\)[^}]*\}""")
        assertTrue(
            rowBgRegex.containsMatchIn(css),
            ".table-row must define an opaque base background (var(--color-surface)) so inherited cells are opaque against scroll."
        )
    }

    @Test
    fun `table stylesheet does not hardcode template utility selectors or duplicate mechanisms`() {
        // Design system must not hardcode template classes like bg-danger-soft on pinned column rules
        val hardcodedDangerRegex = Regex("""bg-danger-soft[^{]*\.table-cell-pinned""")
        assertFalse(
            hardcodedDangerRegex.containsMatchIn(css),
            "Stylesheet must not hardcode template utility selectors (.bg-danger-soft) for pinned cells; use row inheritance instead."
        )

        // Must not duplicate mechanism with custom property
        assertFalse(
            css.contains("--table-pinned-bg"),
            "Stylesheet must not use duplicate --table-pinned-bg custom property; use background-color: inherit instead."
        )
    }

    @Test
    fun `admin dashboard table rows preserve table-row utility and orphaned danger state`() {
        val driveRow = doc.selectFirst("div[aria-label='Google Drive Files'] tbody tr")
        assertTrue(driveRow != null, "Google Drive Files table must have body rows")
        assertTrue(
            driveRow?.hasClass("table-row") == true,
            "Google Drive Files row must carry 'table-row' class for hover and base background"
        )
        val classAppend = driveRow?.attr("th:classappend") ?: ""
        assertTrue(
            classAppend.contains("bg-danger-soft/50"),
            "Google Drive Files row must conditionally append 'bg-danger-soft/50' for orphaned files"
        )
        val driveFirstCell = driveRow?.selectFirst("td")
        assertTrue(
            driveFirstCell?.hasClass("table-cell-pinned") == true,
            "Google Drive Files row first cell must have 'table-cell-pinned'"
        )

        val cleanupRow = doc.selectFirst("div[aria-label='Cleanup Job History'] tbody tr")
        assertTrue(
            cleanupRow?.hasClass("table-row") == true,
            "Cleanup Job History row must carry 'table-row' class"
        )

        val userRow = doc.getElementsByAttributeValue("th:fragment", "userRow").first()
        assertTrue(
            userRow?.hasClass("table-row") == true,
            "User row fragment must carry 'table-row' class"
        )

        val calendarRow = doc.getElementsByAttributeValue("th:fragment", "calendarRow").first()
        assertTrue(
            calendarRow?.hasClass("table-row") == true,
            "Calendar row fragment must carry 'table-row' class"
        )
    }
}
