package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The widget's behaviour runs in a browser, where JUnit cannot see it. These pin the rules
 * whose violation would build, load and quietly misbehave.
 */
class AssistantScriptGuardTest {

    private val script = Files.readString(Path.of("src/main/resources/static/js/assistant.js"))
    private val layout = Files.readString(Path.of("src/main/resources/templates/layout.html"))
    private val widget = Files.readString(Path.of("src/main/resources/templates/assistant/widget.html"))

    @Test
    fun `listeners bind to document, never document body`() {
        assertFalse(Regex("document\\.body\\s*\\.\\s*addEventListener").containsMatchIn(script),
            "main.js loads in <head>, where document.body is null; a listener on it throws and halts the script.")
    }

    @Test
    fun `the slash shortcut ignores inputs, textareas, selects, editors and modifier keys`() {
        assertTrue(script.contains("event.key !== '/'"))
        assertTrue(script.contains("input, textarea, select, trix-editor"))
        assertTrue(script.contains("isContentEditable"))
        assertTrue(script.contains("event.ctrlKey || event.metaKey || event.altKey"))
    }

    @Test
    fun `the mic is hidden in markup and shown only when SpeechRecognition exists`() {
        assertTrue(Regex("data-assistant-mic\\s+hidden").containsMatchIn(widget), "the mic ships hidden")
        assertTrue(script.contains("window.SpeechRecognition || window.webkitSpeechRecognition"))
        assertTrue(script.contains("if (Recognition)"))
        assertTrue(script.contains("button.hidden = false"))
    }

    @Test
    fun `icons built in JavaScript go through renderIcon`() {
        assertTrue(script.contains("renderIcon("))
        assertFalse(script.contains("material-symbols-outlined"), "use renderIcon, not hand-written icon markup")
    }

    @Test
    fun `the surface is a native dialog opened with show or showModal`() {
        assertTrue(widget.contains("<dialog id=\"assistantSurface\""))
        assertTrue(script.contains("surface.show()"))
        assertTrue(script.contains("surface.showModal()"))
    }

    @Test
    fun `the layout loads the script and widget only for a signed-in page that has the assistant`() {
        assertTrue(layout.contains("th:if=\"\${assistantNav != null}\""))
        assertTrue(layout.contains("@{/js/assistant.js}"))
        assertTrue(layout.contains("assistant/widget :: widget"))
    }

    @Test
    fun `the floating bar steps aside while the desktop panel is open, and the slash key then focuses the composer`() {
        assertTrue(script.contains("bar.hidden = surface.open && wide.matches"))
        assertTrue(script.contains("surface.addEventListener('close'"))
        assertTrue(script.contains("if (surface.open) { composerInput.focus(); return; }"))
    }

    @Test
    fun `the composer's conversation id is read from a marker in the swapped fragment, not from an out-of-band swap`() {
        assertTrue(script.contains("[data-conversation-id]"))
        assertTrue(script.contains("[data-reset-conversation]"))
        assertFalse(Files.readString(Path.of("src/main/resources/templates/assistant/fragments.html")).contains("hx-swap-oob"))
    }

    @Test
    fun `a failed request leaves an error in the thread, because main js writes to main which a phone sheet covers`() {
        assertTrue(script.contains("htmx:responseError"))
        assertTrue(script.contains("htmx:sendError"))
        assertTrue(script.contains("closest('#assistantComposer')"))
    }

    @Test
    fun `a reply does not wipe text typed while waiting, and a send is not started while one is in flight`() {
        assertTrue(script.contains("htmx:beforeRequest"))
        assertTrue(script.contains("composerInput.value === sentText"))
        assertTrue(script.contains("composer.classList.contains('htmx-request')"))
    }
}
