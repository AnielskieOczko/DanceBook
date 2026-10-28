package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Guards against regressions in the auto-logout inactivity countdown dialog (Issue #417).
 *
 * Requirements:
 * - Native HTML5 <dialog> modal in layout.html with role='alertdialog' and accessible labels.
 * - Works on mobile (375px) with responsive dialog sizing.
 * - Reduced-motion CSS rules in input.css disabling transitions and animations.
 * - Absent from the sign-in page (login.html).
 * - Full bilingual parity for dialog titles, buttons, and plural countdown templates.
 * - Script in main.js coordinates cross-tab activity via localStorage, uses timestamp-based math,
 *   provides a 60-second warning lead time, and supports quiet dismissal on activity.
 */
class AutoLogoutWarningGuardTest {

    private val layoutFile = File("src/main/resources/templates/layout.html")
    private val loginFile = File("src/main/resources/templates/login.html")
    private val inputCssFile = File("src/main/resources/frontend/input.css")
    private val mainJsFile = File("src/main/resources/static/js/main.js")
    private val messagesEnFile = File("src/main/resources/messages.properties")
    private val messagesPlFile = File("src/main/resources/messages_pl.properties")

    @Test
    fun `layout html contains accessible inactivity logout dialog with proper controls`() {
        assertTrue(layoutFile.isFile, "layout.html must exist")
        val doc = Jsoup.parse(layoutFile.readText())

        val modal = doc.selectFirst("dialog#inactivityLogoutModal")
        assertNotNull(modal, "dialog#inactivityLogoutModal must exist in layout.html")
        assertEquals("dialog", modal!!.tagName())
        assertEquals("alertdialog", modal.attr("role"), "Modal must have role='alertdialog'")
        assertEquals("true", modal.attr("aria-modal"), "Modal must have aria-modal='true'")
        assertEquals("inactivityDialogTitle", modal.attr("aria-labelledby"), "Must label modal with title id")
        assertEquals("inactivityCountdown", modal.attr("aria-describedby"), "Must describe modal with countdown id")

        // Mobile responsiveness
        assertTrue(
            modal.hasClass("w-[calc(100%-2rem)]") || modal.hasClass("max-w-[28rem]"),
            "Modal must have responsive width classes for 375px screens"
        )

        // Title
        val title = modal.selectFirst("#inactivityDialogTitle")
        assertNotNull(title, "#inactivityDialogTitle must exist")
        assertTrue(title!!.attr("th:text").contains("auth.logout_warning.title"))

        // Countdown text
        val countdown = modal.selectFirst("#inactivityCountdown")
        assertNotNull(countdown, "#inactivityCountdown must exist")
        assertTrue(countdown!!.attr("th:text").contains("auth.logout_warning.countdown_initial"))

        // Stay signed in button
        val stayBtn = modal.selectFirst("#inactivityStaySignedInBtn")
        assertNotNull(stayBtn, "#inactivityStaySignedInBtn must exist")
        assertEquals("button", stayBtn!!.attr("type"))
        assertTrue(stayBtn.hasClass("btn-primary"), "Stay signed in must be a primary button")
        assertTrue(stayBtn.attr("th:text").contains("auth.logout_warning.stay_signed_in"))

        // Sign out now link/button
        val signOutBtn = modal.selectFirst("#inactivitySignOutBtn")
        assertNotNull(signOutBtn, "#inactivitySignOutBtn must exist")
        assertTrue(signOutBtn!!.attr("th:text").contains("auth.logout_warning.sign_out_now"))
    }

    @Test
    fun `login html never contains inactivity logout dialog or main js`() {
        assertTrue(loginFile.isFile, "login.html must exist")
        val doc = Jsoup.parse(loginFile.readText())

        val modal = doc.selectFirst("#inactivityLogoutModal")
        assertTrue(modal == null, "login.html must never contain #inactivityLogoutModal")

        val scripts = doc.select("script[src]")
        for (script in scripts) {
            assertFalse(
                script.attr("src").contains("main.js"),
                "login.html must not load main.js"
            )
        }
    }

    @Test
    fun `both message bundles contain all required inactivity warning labels`() {
        val requiredKeys = listOf(
            "auth.logout_warning.title",
            "auth.logout_warning.stay_signed_in",
            "auth.logout_warning.sign_out_now",
            "auth.logout_warning.countdown_initial",
            "js.auth.logout_warning_title",
            "js.auth.stay_signed_in",
            "js.auth.sign_out_now",
            "js.auth.logout_countdown_one",
            "js.auth.logout_countdown_few",
            "js.auth.logout_countdown_many"
        )

        val enProps = Properties().apply { messagesEnFile.reader(Charsets.UTF_8).use { load(it) } }
        val plProps = Properties().apply { messagesPlFile.reader(Charsets.UTF_8).use { load(it) } }

        for (key in requiredKeys) {
            assertTrue(enProps.containsKey(key), "messages.properties must contain $key")
            assertTrue(enProps.getProperty(key).isNotBlank(), "$key in messages.properties must not be blank")

            assertTrue(plProps.containsKey(key), "messages_pl.properties must contain $key")
            assertTrue(plProps.getProperty(key).isNotBlank(), "$key in messages_pl.properties must not be blank")
        }
    }

    @Test
    fun `input css disables inactivity dialog animation under reduced motion`() {
        assertTrue(inputCssFile.isFile, "input.css must exist")
        val content = inputCssFile.readText()

        assertTrue(
            content.contains("dialog#inactivityLogoutModal"),
            "input.css must target dialog#inactivityLogoutModal in reduced-motion section"
        )
        val reducedMotionIdx = content.indexOf("@media (prefers-reduced-motion: reduce)")
        val dialogRuleIdx = content.indexOf("dialog#inactivityLogoutModal")
        assertTrue(
            reducedMotionIdx != -1 && dialogRuleIdx > reducedMotionIdx,
            "dialog#inactivityLogoutModal rule must be inside prefers-reduced-motion block"
        )
    }

    @Test
    fun `main js handles multi-tab synchronization and timestamp-based countdown`() {
        assertTrue(mainJsFile.isFile, "main.js must exist")
        val content = mainJsFile.readText()

        // Multi-tab sync via localStorage & storage event
        assertTrue(content.contains("dancebook:lastActivityTime"), "main.js must use cross-tab storage key")
        assertTrue(content.contains("window.addEventListener('storage'"), "main.js must listen for storage events")

        // 60 second warning lead time
        assertTrue(content.contains("WARNING_LEAD_TIME_MS = 60 * 1000"), "main.js must define 60s warning lead time")
        assertTrue(content.contains("Math.min(WARNING_LEAD_TIME_MS, inactivityLogoutMs)"), "main.js must bound warning window")

        // Timestamp calculation and plural countdown formatting
        assertTrue(content.contains("Math.ceil(remainingMs / 1000)"), "main.js must compute countdown from remainingMs")
        assertTrue(content.contains("I18n.t('js.auth.logout_countdown_one'"), "main.js must use single second key")
        assertTrue(content.contains("I18n.t('js.auth.logout_countdown_few'"), "main.js must use few seconds key")
        assertTrue(content.contains("I18n.t('js.auth.logout_countdown_many'"), "main.js must use many seconds key")

        // Quiet dismissal on activity
        assertTrue(content.contains("closeWarningModal(false)"), "main.js must close warning modal quietly on activity")

        // Sign-in page guard
        assertTrue(content.contains("window.location.pathname === '/login'"), "main.js must guard against running on /login")
    }
}
