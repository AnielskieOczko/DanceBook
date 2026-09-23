package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

import org.jsoup.parser.Parser

/**
 * Guards the responsive and accessible dense-table pattern on the admin dashboard (Issue #131).
 *
 * All four dense tables on the admin screen — Registered Users, Cleanup Job History, Google Drive Files,
 * and Training Calendars — must:
 * 1. Maintain their overflow-x-auto container with tabindex="0", role="region", and an aria-label naming the table.
 * 2. Pin the first identifying column using table-header-pinned on the first <th> and table-cell-pinned on the first <td>.
 * 3. Expose column headers as <th> elements and preserve all columns without dropping or hiding fields.
 */
class AdminDenseTableTemplateTest {

    private val templateFile = File("src/main/resources/templates/admin/dashboard.html")
    private val doc by lazy {
        assertTrue(templateFile.isFile, "Template file not found at ${templateFile.absolutePath}")
        Jsoup.parse(templateFile.readText(), "", Parser.xmlParser())
    }

    @Test
    fun `all four dense table scroll containers are focusable regions with accessible names`() {
        val expectedRegions = mapOf(
            "Registered Users" to "Registered Users",
            "Cleanup Job History" to "Cleanup Job History",
            "Google Drive Files" to "Google Drive Files",
            "Training Calendars" to "Training Calendars"
        )

        val scrollContainers = doc.select("div.overflow-x-auto[role=region]")
        assertEquals(4, scrollContainers.size, "Expected exactly 4 dense table scroll containers with role='region'")

        for ((_, label) in expectedRegions) {
            val container = doc.selectFirst("div.overflow-x-auto[role=region][aria-label='$label']")
            assertTrue(
                container != null,
                "Expected a scroll container with aria-label='$label', role='region', and overflow-x-auto"
            )
            assertEquals(
                "0",
                container?.attr("tabindex"),
                "Scroll container '$label' must be focusable with tabindex='0'"
            )
        }
    }

    @Test
    fun `all four dense tables pin their first column header with table-header-pinned`() {
        val tables = doc.select("div.overflow-x-auto[role=region] > table")
        assertEquals(4, tables.size, "Expected 4 tables inside the scroll regions")

        for (table in tables) {
            val firstTh = table.selectFirst("thead tr th")
            assertTrue(firstTh != null, "Table in scroll region must have a first <th>")
            assertTrue(
                firstTh?.hasClass("table-header-pinned") == true,
                "First <th> ('${firstTh?.text()}') must have the 'table-header-pinned' class"
            )
        }
    }

    @Test
    fun `all table column headers are exposed as th elements`() {
        val userHeaders = doc.select("div[aria-label='Registered Users'] thead th").eachText()
        assertEquals(listOf("User", "Role", "Joined", "Actions"), userHeaders)

        val cleanupHeaders = doc.select("div[aria-label='Cleanup Job History'] thead th").eachText()
        assertEquals(listOf("Executed At", "Type", "Files Deleted", "Details"), cleanupHeaders)

        val driveHeaders = doc.select("div[aria-label='Google Drive Files'] thead th").eachText()
        assertEquals(listOf("File Name", "Size", "Status"), driveHeaders)

        val calendarHeaders = doc.select("div[aria-label='Training Calendars'] thead th").eachText()
        assertEquals(listOf("Display Name", "Google ID", "Last Synced", "Default", "Enabled", "Actions"), calendarHeaders)
    }

    @Test
    fun `identifying body cells in table rows are pinned with table-cell-pinned`() {
        // Cleanup logs row
        val cleanupCell = doc.selectFirst("div[aria-label='Cleanup Job History'] tbody tr td")
        assertTrue(
            cleanupCell?.hasClass("table-cell-pinned") == true,
            "Cleanup history row first <td> must have 'table-cell-pinned' class"
        )

        // Drive files row
        val driveCell = doc.selectFirst("div[aria-label='Google Drive Files'] tbody tr td")
        assertTrue(
            driveCell?.hasClass("table-cell-pinned") == true,
            "Drive files row first <td> must have 'table-cell-pinned' class"
        )

        // userRow fragment
        val userRow = doc.getElementsByAttributeValue("th:fragment", "userRow").first()
        val userRowFirstTd = userRow?.selectFirst("td")
        assertTrue(
            userRowFirstTd?.hasClass("table-cell-pinned") == true,
            "userRow fragment first <td> must have 'table-cell-pinned' class"
        )

        // calendarRow fragment
        val calendarRow = doc.getElementsByAttributeValue("th:fragment", "calendarRow").first()
        val calendarRowFirstTd = calendarRow?.selectFirst("td")
        assertTrue(
            calendarRowFirstTd?.hasClass("table-cell-pinned") == true,
            "calendarRow fragment first <td> must have 'table-cell-pinned' class"
        )
    }
}
