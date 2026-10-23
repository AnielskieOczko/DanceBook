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
    fun `the surface has viewport-fixed positioning and layering classes`() {
        val dialogTag = widget.substringAfter("<dialog id=\"assistantSurface\"").substringBefore(">")
        assertTrue(dialogTag.contains("fixed"), "must be fixed to the viewport, not in document flow")
        assertTrue(dialogTag.contains("inset-0"), "must anchor to viewport edges on mobile")
        assertTrue(dialogTag.contains("z-50"), "must layer above page headers and content")
        assertTrue(dialogTag.contains("open:flex"), "must be flex only when open so closed dialog does not cover page")
        assertTrue(dialogTag.contains("md:left-auto"), "must unpin left edge on desktop")
        assertTrue(dialogTag.contains("md:right-0"), "must dock to right edge on desktop")
        assertTrue(dialogTag.contains("md:w-[400px]"), "must have 400px width on desktop")
    }

    @Test
    fun `the layout loads the script and widget only for a signed-in page that has the assistant`() {
        assertTrue(layout.contains("th:if=\"\${assistantNav != null}\""))
        assertTrue(layout.contains("@{/js/assistant.js}"))
        assertTrue(layout.contains("assistant/widget :: widget"))
    }

    @Test
    fun `the slash key opens the assistant surface and focuses the composer on desktop`() {
        assertFalse(script.contains("assistantBar"), "assistant.js must not reference assistantBar")
        val slashBranch = script.substringAfter("event.key !== '/'").substringBefore("SpeechRecognition")
        assertTrue(slashBranch.contains("openSurface()"), "slash shortcut must call openSurface()")
        assertTrue(slashBranch.contains("wide.matches"), "slash shortcut must respect wide viewport")
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

    @Test
    fun `a prefill chip opens the assistant and fills the composer without sending`() {
        assertTrue(script.contains("[data-assistant-prefill]"))
        val branch = script.substringAfter("[data-assistant-prefill]").substringBefore("const prompt")
        assertTrue(branch.contains("openSurface()"), "it opens the surface")
        assertTrue(branch.contains("composerInput.value"), "it fills the composer")
        assertFalse(branch.contains("requestSubmit"), "it must not send: the user still has to say what happened")
    }

    @Test
    fun `expand button is hidden on mobile and collapse button is hidden by default`() {
        val expand = widget.substringAfter("id=\"assistantExpandBtn\"").substringBefore(">")
        assertTrue(expand.contains("hidden md:flex"), "expand control must be hidden below desktop")
        val collapse = widget.substringAfter("id=\"assistantCollapseBtn\"").substringBefore(">")
        assertTrue(collapse.contains("hidden"), "collapse control must start hidden")
        assertTrue(widget.contains("name='open_in_full'"), "expand icon must be open_in_full")
        assertTrue(widget.contains("name='close_fullscreen'"), "collapse icon must be close_fullscreen")
    }

    @Test
    fun `the rail is 292px wide, border-right, and hidden by default`() {
        val rail = widget.substringAfter("id=\"assistantRail\"").substringBefore(">")
        assertTrue(rail.contains("w-[292px]"), "rail width must be 292px per mock")
        assertTrue(rail.contains("border-r"), "rail must have right border")
        assertTrue(rail.contains("hidden"), "rail must be hidden until expanded")
    }

    @Test
    fun `expanding adds is-expanded class and focuses collapse button, collapsing restores focus to expand button`() {
        assertTrue(script.contains("surface.classList.add('is-expanded')"))
        assertTrue(script.contains("surface.classList.remove('is-expanded')"))
        assertTrue(script.contains("collapseBtn.focus()"))
        assertTrue(script.contains("expandBtn.focus()"))
    }

    @Test
    fun `escape and cancel restore collapsed panel instead of closing the dialog when expanded`() {
        assertTrue(script.contains("surface.addEventListener('cancel'"))
        val cancelBranch = script.substringAfter("surface.addEventListener('cancel'").substringBefore("});")
        assertTrue(cancelBranch.contains("event.preventDefault()"))
        assertTrue(cancelBranch.contains("collapse()"))

        val escapeBranch = script.substringAfter("event.key === 'Escape'").substringBefore("assistant-conversation")
        assertTrue(escapeBranch.contains("surface.classList.contains('is-expanded')"))
        assertTrue(escapeBranch.contains("collapse()"))
    }

    @Test
    fun `openSurface expands on each open when data-full-window is true and screen is wide, while firstOpen runs once`() {
        val openFunc = script.substringAfter("function openSurface()").substringBefore("function updateTitle()")
        assertTrue(openFunc.contains("if (firstOpen)"), "firstOpen guard must be present")
        assertTrue(openFunc.contains("thread.dispatchEvent(new CustomEvent('assistant-open'))"), "thread setup must be dispatched")
        assertTrue(openFunc.contains("surface.getAttribute('data-full-window') === 'true'"), "must check data-full-window attribute")
        assertTrue(openFunc.contains("expand()"), "must call expand when full-window is set")

        // In widget.html, data-full-window is present but is-expanded is not pre-rendered on the surface
        val surfaceTag = widget.substringAfter("<dialog id=\"assistantSurface\"").substringBefore(">")
        assertTrue(surfaceTag.contains("data-full-window"), "surface dialog must carry data-full-window")
        assertFalse(surfaceTag.contains("is-expanded"), "surface dialog must not have server-rendered is-expanded class")
    }

    @Test
    fun `the composer is a textarea starting at about 3 lines tall with 24px radius`() {
        assertTrue(widget.contains("<textarea id=\"assistantComposerInput\""))
        val textareaTag = widget.substringAfter("<textarea id=\"assistantComposerInput\"").substringBefore(">")
        assertTrue(textareaTag.contains("rows=\"3\""), "textarea must specify rows=3")
        assertTrue(textareaTag.contains("rounded-[24px]"), "textarea must use 24px multi-line surface token")
    }

    @Test
    fun `enter key sends without shift on desktop, while shift-enter and phone enter add newlines`() {
        assertTrue(script.contains("event.key === 'Enter'"))
        val enterBranch = script.substringAfter("composerInput.addEventListener('keydown'").substringBefore("composer.addEventListener('submit'")
        assertTrue(enterBranch.contains("if (event.isComposing || event.keyCode === 229) return;"), "IME composition must not trigger send")
        assertTrue(enterBranch.contains("if (!wide.matches) return;"), "phone return key must not trigger send")
        assertTrue(enterBranch.contains("if (event.shiftKey) return;"), "shift-enter must not trigger send")
        assertTrue(enterBranch.contains("event.preventDefault()"), "enter on desktop must prevent default newline")
        assertTrue(enterBranch.contains("composer.requestSubmit()"), "enter on desktop must submit the composer")
    }

    @Test
    fun `empty or whitespace-only messages are not sent`() {
        val submitBranch = script.substringAfter("composer.addEventListener('submit'").substringBefore("surface.addEventListener('close'")
        assertTrue(submitBranch.contains("!composerInput.value.trim()"), "submit must check for trimmed text")
        assertTrue(submitBranch.contains("event.preventDefault()"), "whitespace message submit must be prevented")
    }

    @Test
    fun `composer auto-resizes with text and resets on assistant-sent`() {
        assertTrue(script.contains("function resizeComposer()"))
        assertTrue(script.contains("composerInput.addEventListener('input', resizeComposer)"))
        val sentBranch = script.substringAfter("document.addEventListener('assistant-sent'").substringBefore("document.addEventListener('htmx:afterSwap'")
        assertTrue(sentBranch.contains("resizeComposer()"), "clearing composer must reset its height")
    }

    @Test
    fun `input css defines assistant composer sizing and multi-line styling`() {
        val css = Files.readString(Path.of("src/main/resources/frontend/input.css"))
        assertTrue(css.contains("#assistantComposerInput"))
        val cssRule = css.substringAfter("#assistantComposerInput").substringBefore("}")
        assertTrue(cssRule.contains("field-sizing: content"))
        assertTrue(cssRule.contains("border-radius: var(--radius-field)"))
        assertTrue(cssRule.contains("min-height"))
        assertTrue(cssRule.contains("max-height"))
        assertTrue(cssRule.contains("overflow-y: auto"))
    }
}

