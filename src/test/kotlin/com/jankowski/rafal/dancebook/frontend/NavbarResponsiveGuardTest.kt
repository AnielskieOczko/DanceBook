package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against regressions in responsive navbar breakpoints (Issue #254).
 *
 * At tablet width (768px - 1024px), desktop navigation links plus trailing icons
 * require ~1044px minimum, overflowing the viewport horizontally.
 *
 * To prevent horizontal scrolling at tablet widths, the desktop navigation switches
 * at the `xl` breakpoint (1280px), while tablet and mobile viewports (< 1280px) use
 * the compact mobile pattern (top bar with brand, notifications, and avatar dropdown;
 * bottom navigation bar for primary tabs).
 */
class NavbarResponsiveGuardTest {

    private val layoutFile = File("src/main/resources/templates/layout.html")

    @Test
    fun `desktop navbar switches at xl breakpoint to avoid tablet overflow`() {
        assertTrue(layoutFile.isFile, "layout.html must exist")
        val doc = Jsoup.parse(layoutFile.readText())

        // Desktop nav must be hidden below xl
        val desktopNav = doc.selectFirst("header nav")
        assertNotNull(desktopNav, "Desktop nav must exist in header")
        assertTrue(desktopNav!!.hasClass("hidden"), "Desktop nav must have hidden class")
        assertTrue(desktopNav.hasClass("xl:flex"), "Desktop nav must show only at xl:flex")
        assertFalse(desktopNav.hasClass("md:flex"), "Desktop nav must not switch at md:flex (causes tablet overflow #254)")
        assertFalse(desktopNav.hasClass("lg:flex"), "Desktop nav must not switch at lg:flex (overflows at 1024px)")
    }

    @Test
    fun `bottom navigation bar is active up to xl breakpoint`() {
        assertTrue(layoutFile.isFile, "layout.html must exist")
        val doc = Jsoup.parse(layoutFile.readText())

        val bottomNav = doc.selectFirst("body > nav")
        assertNotNull(bottomNav, "Bottom nav must exist in body")
        assertTrue(bottomNav!!.hasClass("xl:hidden"), "Bottom nav must be hidden only at xl:hidden")
        assertFalse(bottomNav.hasClass("md:hidden"), "Bottom nav must not hide at md:hidden")

        val body = doc.selectFirst("body")
        assertNotNull(body, "Body element must exist")
        assertTrue(body!!.hasClass("pb-24"), "Body must have pb-24 for bottom nav bar clearance")
        assertTrue(body.hasClass("xl:pb-0"), "Body must clear bottom padding at xl:pb-0")
        assertFalse(body.hasClass("md:pb-0"), "Body must not clear bottom padding at md:pb-0")
    }

    @Test
    fun `profile dropdown and admin controls match xl breakpoint`() {
        assertTrue(layoutFile.isFile, "layout.html must exist")
        val doc = Jsoup.parse(layoutFile.readText())

        // Mobile / tablet avatar details dropdown
        val profileMenu = doc.selectFirst("#profile-menu")
        assertNotNull(profileMenu, "#profile-menu must exist")
        assertTrue(profileMenu!!.hasClass("xl:hidden"), "#profile-menu must be hidden only at xl:hidden")
        assertFalse(profileMenu.hasClass("md:hidden"), "#profile-menu must not hide at md:hidden")

        // Desktop avatar container
        val desktopAvatar = doc.selectFirst("header a[href='/profile']")?.parent()
        assertNotNull(desktopAvatar, "Desktop avatar link must exist")
        assertTrue(desktopAvatar!!.hasClass("hidden"), "Desktop avatar must be hidden below xl")
        assertTrue(desktopAvatar.hasClass("xl:block"), "Desktop avatar must show at xl:block")
        assertFalse(desktopAvatar.hasClass("md:block"), "Desktop avatar must not show at md:block")

        // Admin icon in header
        val adminIcon = doc.selectFirst("header a[href='/admin']")
        assertNotNull(adminIcon, "Admin header icon must exist")
        assertTrue(adminIcon!!.hasClass("hidden"), "Admin header icon must be hidden below xl")
        assertTrue(adminIcon.hasClass("xl:flex"), "Admin header icon must show at xl:flex")
        assertFalse(adminIcon.hasClass("md:flex"), "Admin header icon must not show at md:flex")
    }
}
