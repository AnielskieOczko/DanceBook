package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards header action button alignment (#424).
 *
 * Ensures:
 * 1. Header actions (primary action, overflow menu, toolbar, list Add button) sit on the right
 *    in the title row across all breakpoints (mobile 390px and desktop).
 * 2. The page title wraps with break-words min-w-0 and does not force full-width wrapping on mobile.
 * 3. Full-width primary buttons are not used in headers (reserved only for form save/submit bars).
 * 4. Home "+ New" menu dropdown is aligned right-0 on mobile and desktop.
 */
class HeaderActionsAlignmentTest {

    private val pageFragmentFile = File("src/main/resources/templates/fragments/page.html")
    private val homeMenuFile = File("src/main/resources/templates/home/new-menu.html")
    private val listsFormFile = File("src/main/resources/templates/lists/form.html")

    @Test
    fun `pageHeader fragment enforces right-aligned nowrap header row and wrapping title`() {
        assertTrue(pageFragmentFile.isFile, "page.html must exist")
        val content = pageFragmentFile.readText()

        // Main header row must not wrap actions below title on mobile
        assertFalse(content.contains("flex-wrap sm:flex-nowrap"), "Main header row must not use flex-wrap sm:flex-nowrap")

        // Title div must not take w-full on mobile
        assertFalse(content.contains("w-full sm:w-auto sm:flex-1"), "Title container must not take w-full on mobile")
        assertTrue(content.contains("page-header mb-0 min-w-0 flex-1"), "Title container must always be flex-1 min-w-0")

        // Title span must allow wrapping
        assertTrue(content.contains("class=\"break-words min-w-0\""), "Title span must have break-words min-w-0 for long titles")

        // Toolbar container must not take w-full on mobile
        assertFalse(content.contains("shrink-0 w-full sm:w-auto"), "Toolbar container must not take w-full on mobile")

        // Detail actions container must not take w-full on mobile
        assertFalse(content.contains("flex items-center gap-2 w-full sm:w-auto"), "Detail actions must not take w-full on mobile")

        // Primary action in detail actions must not take flex-1 or w-full on mobile
        assertFalse(content.contains("btn-primary btn-sm min-h-[44px] flex-1"), "Primary action must not take flex-1")
    }

    @Test
    fun `home new menu aligns dropdown to right on mobile and desktop`() {
        assertTrue(homeMenuFile.isFile, "new-menu.html must exist")
        val content = homeMenuFile.readText()

        assertTrue(content.contains("right-0 top-full"), "homeNewMenu dropdown must align to right-0")
        assertFalse(content.contains("left-0 sm:left-auto"), "homeNewMenu dropdown must not align to left-0 on mobile")
    }

    @Test
    fun `lists form save button slot does not span full width on mobile`() {
        assertTrue(listsFormFile.isFile, "lists/form.html must exist")
        val content = listsFormFile.readText()

        assertFalse(content.contains("w-full sm:w-auto"), "saveButtonSlot button must not take w-full on mobile")
    }

    @Test
    fun `lists form save button shows a short label on phones so the title is not squeezed`() {
        val content = listsFormFile.readText()

        assertTrue(content.contains("class=\"sm:hidden\" th:text=\"#{common.save}\""), "phones must show the short Save label")
        assertTrue(content.contains("class=\"hidden sm:inline\""), "the long label must be hidden below sm")
    }
}
