package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guard test ensuring that JavaScript client files under static/js do not regress to hardcoded
 * English strings, and instead route all user-visible text through the I18n helper.
 */
class JsHardcodedStringsGuardTest {

    private val jsDir = File("src/main/resources/static/js")

    @Test
    fun `assistant js uses I18n instead of hardcoded English error and dictate text`() {
        val file = File(jsDir, "assistant.js")
        assertTrue(file.isFile, "assistant.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("Could not get a response. Please try again."),
            "assistant.js should not contain hardcoded English thread error"
        )
        assertTrue(
            content.contains("I18n.t('js.assistant.error')"),
            "assistant.js should use I18n.t for thread error"
        )
        assertTrue(
            content.contains("I18n.t('assistant.dictate')"),
            "assistant.js should use I18n.t for dictate tooltip"
        )
        assertTrue(
            content.contains("I18n.t('assistant.stop_dictating')"),
            "assistant.js should use I18n.t for stop dictating tooltip"
        )
    }

    @Test
    fun `main js uses I18n for alerts, bulk actions, and htmx errors`() {
        val file = File(jsDir, "main.js")
        assertTrue(file.isFile, "main.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("Request failed ("),
            "main.js should not contain hardcoded generic error"
        )
        assertFalse(
            content.contains("Network error. Please check your connection and try again."),
            "main.js should not contain hardcoded network error"
        )
        assertTrue(
            content.contains("showErrorAlert('js.error.request_failed_status'"),
            "main.js should use bundle key for status error"
        )
        assertTrue(
            content.contains("showErrorAlert('js.error.network')"),
            "main.js should use bundle key for sendError"
        )
        assertTrue(
            content.contains("I18n.t('common.dismiss')"),
            "main.js should use I18n.t for dismiss button aria-label"
        )
    }

    @Test
    fun `training-event-form js uses I18n for weekdays and recurrence`() {
        val file = File(jsDir, "training-event-form.js")
        assertTrue(file.isFile, "training-event-form.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("The end date must not be before the first session."),
            "training-event-form.js should not have hardcoded repeat summary error"
        )
        assertFalse(
            content.contains("'Weekly on this weekday'"),
            "training-event-form.js should not have hardcoded repeat weekly option"
        )
        assertTrue(
            content.contains("I18n.t('js.training.repeat.end_before_start')"),
            "training-event-form.js should use I18n.t for end before start message"
        )
    }

    @Test
    fun `training-calendar js uses I18n for calendar buttons and errors`() {
        val file = File(jsDir, "training-calendar.js")
        assertTrue(file.isFile, "training-calendar.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("Nothing scheduled. Tap + to add a session."),
            "training-calendar.js should not have hardcoded empty agenda"
        )
        assertFalse(
            content.contains("'Could not load sessions'"),
            "training-calendar.js should not have hardcoded sessions load error"
        )
        assertTrue(
            content.contains("I18n.t('js.calendar.today')"),
            "training-calendar.js should use I18n.t for button today"
        )
        assertTrue(
            content.contains("I18n.t('js.calendar.agenda.empty')"),
            "training-calendar.js should use I18n.t for empty agenda"
        )
    }

    @Test
    fun `dance-figure-form js uses I18n for step set combinations and step rows`() {
        val file = File(jsDir, "dance-figure-form.js")
        assertTrue(file.isFile, "dance-figure-form.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("At least one step combination is required."),
            "dance-figure-form.js should not have hardcoded combination required alert"
        )
        assertFalse(
            content.contains("Delete Combination"),
            "dance-figure-form.js should not have hardcoded delete combination label"
        )
        assertTrue(
            content.contains("I18n.t('js.figure.alert_combination_required')"),
            "dance-figure-form.js should use I18n.t for combination required alert"
        )
        assertTrue(
            content.contains("I18n.t('figures.form.steps.delete_combination')"),
            "dance-figure-form.js should use I18n.t for delete combination button"
        )
    }

    @Test
    fun `drive-form js uses I18n for upload buttons and warnings`() {
        val file = File(jsDir, "drive-form.js")
        assertTrue(file.isFile, "drive-form.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("'Uploading...'"),
            "drive-form.js should not have hardcoded uploading button text"
        )
        assertFalse(
            content.contains("'Click to select a video file...'"),
            "drive-form.js should not have hardcoded select file label"
        )
        assertTrue(
            content.contains("I18n.t('notes.form.drive.uploading')"),
            "drive-form.js should use I18n.t for uploading"
        )
        assertTrue(
            content.contains("I18n.t('js.drive.confirm_unlink')"),
            "drive-form.js should use I18n.t for unlink confirmation"
        )
    }

    @Test
    fun `guided-figure-edit js uses I18n for schema, diff, and toast messages`() {
        val file = File(jsDir, "guided-figure-edit.js")
        assertTrue(file.isFile, "guided-figure-edit.js not found")
        val content = file.readText()

        assertFalse(
            content.contains("Failed to load models list from server."),
            "guided-figure-edit.js should not have hardcoded models list error"
        )
        assertFalse(
            content.contains("Please paste JSON data first."),
            "guided-figure-edit.js should not have hardcoded paste json warning"
        )
        assertTrue(
            content.contains("I18n.t('js.guided.error.models_load')"),
            "guided-figure-edit.js should use I18n.t for models load error"
        )
        assertTrue(
            content.contains("I18n.t('js.guided.diff.style_not_found')"),
            "guided-figure-edit.js should use I18n.t for style not found"
        )
    }
}
