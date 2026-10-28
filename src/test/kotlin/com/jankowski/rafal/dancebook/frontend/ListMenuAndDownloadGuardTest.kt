package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards two list-page behaviours in main.js (#416):
 * - a PDF download leaves the page in place, so it must not start the top progress bar, which nothing would end;
 * - a row's ⋯ menu inside a desktop table's overflow-x-auto wrapper is pinned to the viewport, not clipped by it.
 */
class ListMenuAndDownloadGuardTest {

    private val js = File("src/main/resources/static/js/main.js").readText()

    @Test
    fun `pdf downloads do not start the progress bar`() {
        assertTrue(js.contains("function isPdfDownload(link)"), "PDF links must be recognised")
        assertTrue(js.contains("pathname.endsWith('/pdf')"), "every .../pdf link is a download")
        assertTrue(js.contains("skipUnloadProgressUntil"), "beforeunload must not start the bar for a download")
        assertTrue(js.contains("if (Date.now() < skipUnloadProgressUntil) return;"))
    }

    @Test
    fun `row menus inside a scrolling table wrapper are pinned to the viewport`() {
        assertTrue(js.contains("function positionMenuInScrollContainer(btn, dropdown)"))
        assertTrue(js.contains("btn.closest('.overflow-x-auto')"), "only menus in a scroll wrapper are pinned")
        assertTrue(js.contains("dropdown.style.position = 'fixed'"))
        assertTrue(js.contains("positionMenuInScrollContainer(menuBtn, dropdown);"), "the toggle handler must call it on open")
        assertTrue(js.contains("window.addEventListener('scroll', closePinnedMenus, true)"), "a pinned menu closes on scroll")
    }
}
