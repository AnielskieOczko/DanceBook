package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards against regressions in dropdown and popup menu behavior (Issue #206).
 *
 * Acceptance requirements:
 * 1. The dashboard "+ New" menu, mobile profile menu, and choreography color guide
 *    close on outside click and Escape.
 * 2. Focus returns to the trigger on Escape.
 * 3. 3-dot action menus and notification dropdown share the same close-on-outside
 *    and Escape with trigger focus restoration.
 * 4. Inline disclosure <details> (e.g. Drive manual input, AI options) are preserved
 *    and not treated as auto-closing dropdowns.
 * 5. Event listeners in `main.js` must bind to `document`, never `document.body`,
 *    because `main.js` evaluates synchronously in `<head>` where `document.body` is null.
 */
class DropdownMenuScriptGuardTest {

    private val mainJs = Files.readString(Path.of("src/main/resources/static/js/main.js"))
    private val templatesDir = File("src/main/resources/templates")

    @Test
    fun `listeners bind to document, never document body`() {
        assertFalse(
            Regex("""document\.body\s*\.\s*addEventListener""").containsMatchIn(mainJs),
            "main.js loads in <head>, where document.body is null; a listener on it throws and halts the script."
        )
    }

    @Test
    fun `main js defines unified closeAllMenus function handling all dropdown types`() {
        assertTrue(mainJs.contains("function closeAllMenus("), "main.js must define closeAllMenus helper")
        // Checks details dropdowns
        assertTrue(mainJs.contains("details.js-dropdown[open]"), "closeAllMenus must query open details dropdowns")
        assertTrue(mainJs.contains("#home-new-menu details[open]"), "closeAllMenus must support #home-new-menu")
        assertTrue(mainJs.contains("removeAttribute('open')"), "closeAllMenus must remove open attribute to close details")
        // Checks 3-dot action menus
        assertTrue(mainJs.contains(".js-menu-dropdown:not(.hidden)"), "closeAllMenus must query visible action menus")
        assertTrue(mainJs.contains("dropdown.classList.add('hidden')"), "closeAllMenus must add hidden to close action menus")
        // Checks notification dropdown
        assertTrue(mainJs.contains("notification-dropdown"), "closeAllMenus must support notification dropdown")
        // Preserves clicked menu
        assertTrue(mainJs.contains("exceptElement"), "closeAllMenus must support preserving the clicked menu")
    }

    @Test
    fun `document click handler invokes closeAllMenus for click-outside dismissal`() {
        assertTrue(
            mainJs.contains("closeAllMenus(event.target)"),
            "Click handler must invoke closeAllMenus(event.target) so clicking outside closes open dropdowns"
        )
    }

    @Test
    fun `3-dot and notification toggles close other menus when opened`() {
        assertTrue(
            mainJs.contains("closeAllMenus(menuBtn)"),
            "Clicking a 3-dot menu button must close other open dropdowns and menus"
        )
        assertTrue(
            mainJs.contains("closeAllMenus(bellBtn)"),
            "Clicking notification bell must close other open dropdowns and menus"
        )
    }

    @Test
    fun `escape keydown listener closes open menus and restores focus to trigger`() {
        assertTrue(mainJs.contains("document.addEventListener('keydown'"), "main.js must register keydown listener on document")
        assertTrue(mainJs.contains("event.key !== 'Escape'"), "keydown listener must check for Escape key")
        assertTrue(mainJs.contains("event.target.closest('dialog')"), "keydown listener must not intercept Escape inside native dialogs")
        assertTrue(mainJs.contains("triggerToFocus?.focus()"), "keydown listener must restore focus to trigger")
        assertTrue(mainJs.contains("details.querySelector('summary')"), "trigger for details dropdown is summary")
        assertTrue(mainJs.contains(".js-menu-btn"), "trigger for action dropdown is .js-menu-btn")
        assertTrue(mainJs.contains("notification-bell"), "trigger for notification dropdown is notification-bell")
        assertTrue(mainJs.contains("event.preventDefault()"), "closing a menu on Escape must prevent default")
    }

    @Test
    fun `dropdown details templates carry js-dropdown class and summary trigger`() {
        val newMenuFile = File(templatesDir, "home/new-menu.html")
        assertTrue(newMenuFile.isFile, "new-menu.html must exist")
        val newMenuDoc = Jsoup.parse(newMenuFile.readText())
        val newMenuDetails = newMenuDoc.selectFirst("#home-new-menu details")
        assertNotNull(newMenuDetails, "Home new menu details must exist")
        assertTrue(newMenuDetails!!.hasClass("js-dropdown"), "Home new menu details must have js-dropdown class")
        assertNotNull(newMenuDetails.selectFirst("summary"), "Home new menu details must have summary trigger")

        val layoutFile = File(templatesDir, "layout.html")
        assertTrue(layoutFile.isFile, "layout.html must exist")
        val layoutDoc = Jsoup.parse(layoutFile.readText())
        val profileDetails = layoutDoc.selectFirst("#profile-menu")
        assertNotNull(profileDetails, "Profile menu details must exist")
        assertTrue(profileDetails!!.hasClass("js-dropdown"), "Profile menu details must have js-dropdown class")
        assertNotNull(profileDetails.selectFirst("summary"), "Profile menu details must have summary trigger")

        val choreoFile = File(templatesDir, "choreographies/view.html")
        assertTrue(choreoFile.isFile, "choreographies/view.html must exist")
        val choreoDoc = Jsoup.parse(choreoFile.readText())
        val colorGuideDetails = choreoDoc.selectFirst("#lod-color-guide")
        assertNotNull(colorGuideDetails, "LOD color guide details must exist")
        assertTrue(colorGuideDetails!!.hasClass("js-dropdown"), "LOD color guide details must have js-dropdown class")
        assertNotNull(colorGuideDetails.selectFirst("summary"), "LOD color guide details must have summary trigger")
    }

    @Test
    fun `non-dropdown details elements do not carry js-dropdown class`() {
        val materialFormFile = File(templatesDir, "materials/form.html")
        val materialDoc = Jsoup.parse(materialFormFile.readText())
        for (details in materialDoc.select("details")) {
            assertFalse(
                details.hasClass("js-dropdown"),
                "Manual Drive ID fallback in materials form is an inline disclosure, not a dropdown menu"
            )
        }

        val danceFigureFormFile = File(templatesDir, "dance-figures/form.html")
        val figureDoc = Jsoup.parse(danceFigureFormFile.readText())
        for (details in figureDoc.select("details")) {
            assertFalse(
                details.hasClass("js-dropdown"),
                "Advanced AI parameters in dance figure form is an accordion, not a dropdown menu"
            )
        }
    }

    @Test
    fun `action menu templates pair js-menu-btn and js-menu-dropdown`() {
        val listTemplates = listOf(
            "materials/list.html",
            "lists/index.html",
            "dance-figures/list.html",
            "admin/dashboard.html",
            "choreographies/index.html",
            "choreographies/view.html",
            "training-events/list.html"
        )

        for (relPath in listTemplates) {
            val file = File(templatesDir, relPath)
            assertTrue(file.isFile, "Template $relPath must exist")
            val doc = Jsoup.parse(file.readText())
            val dropdowns = doc.select(".js-menu-dropdown")
            for (dropdown in dropdowns) {
                val prev = dropdown.previousElementSibling()
                val btn = if (prev != null && prev.hasClass("js-menu-btn")) prev else dropdown.parent()?.selectFirst(".js-menu-btn")
                assertNotNull(btn, "Every .js-menu-dropdown in $relPath must have an associated .js-menu-btn trigger")
            }
        }
    }
}
