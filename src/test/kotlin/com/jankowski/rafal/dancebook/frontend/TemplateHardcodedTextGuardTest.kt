package com.jankowski.rafal.dancebook.frontend

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guard test (#247, part of #204): templates must not carry user-visible text that is not bound
 * to a message key. The English text stays in the markup as the prototype value (see AGENTS.md),
 * so the rule is: an element with visible text must bind it with th:text / th:utext, and the
 * user-visible attributes (placeholder, title, aria-label, alt) must have a th:* / th:attr binding.
 */
class TemplateHardcodedTextGuardTest {

    private val templatesDir = File("src/main/resources/templates")

    private val textBindings = listOf("th:text", "th:utext", "th:remove", "th:inline", "th:errors")
    private val textAttributes = listOf("placeholder", "title", "aria-label", "alt")

    /**
     * Templates whose area has not had its Polish sweep yet (see the checklist in #204). Each entry
     * goes away with the sweep that translates it: remove the entry in the same PR. The test fails
     * when an entry no longer has any violation, so this list can only shrink.
     */
    private val pendingSweep = mapOf(
        "assistant/fragments.html" to "assistant history menu, untranslated (#246)",
        "fragments/assistant.html" to "assistant draft cards, untranslated (#246)",
        "dance-categories/form.html" to "collections sweep (#239)",
        "dance-categories/list.html" to "collections sweep (#239)",
        "dance-types/form.html" to "collections sweep (#239)",
        "dance-types/list.html" to "collections sweep (#239)",
        "dance-figures/form.html" to "figures sweep (#238)",
        "dance-figures/view.html" to "figures sweep (#238)",
        "fragments/table.html" to "figures sweep (#238): step table headers",
        "training-calendars/form.html" to "training sweep (#241)",
        "training-calendars/index.html" to "training sweep (#241)",
    )

    /**
     * Narrow allowlist of "<template path>: <text>" entries that are intentionally not translated.
     * Each entry needs a reason.
     */
    private val allowedText = mapOf(
        "admin/dashboard.html: c_...@group.calendar.google.com" to "example calendar id, a literal value",
        "admin/dashboard.html: GOOGLE_REFRESH_TOKEN" to "environment variable name",
        "choreographies/view.html: L" to "lead/follow notation, kept as-is by design (AGENTS.md)",
        "choreographies/view.html: R" to "lead/follow notation, kept as-is by design (AGENTS.md)",
        "fragments/bulk-edit-dialog.html: https://..." to "URL format hint, not a word",
        "materials/form.html: e.g. 1FhnSuC88na_OrlBbgIzCyK8r4U1Jg_pb" to "example Drive file id, a literal value",
        "training-events/calendar.html: Session" to "agenda row template; client script overwrites it with the event title",
    )

    @Test
    fun `templates contain no hardcoded user-visible text`() {
        assertTrue(templatesDir.isDirectory, "Templates directory not found at $templatesDir")
        val violations = mutableListOf<String>()

        templatesDir.walkTopDown().filter { it.isFile && it.extension == "html" }.sortedBy { it.path }.forEach { file ->
            val rel = file.relativeTo(templatesDir).path
            val doc = Jsoup.parse(file.readText(), "", Parser.xmlParser())
            for (el in doc.getAllElements()) {
                if (isBound(el)) continue
                el.textNodes().forEach { node -> checkText(rel, el, node, violations) }
                textAttributes.forEach { attr -> checkAttr(rel, el, attr, violations) }
            }
        }

        val stale = pendingSweep.keys.filter { path -> violations.none { it.startsWith("$path: ") } }
        assertTrue(stale.isEmpty(), "pendingSweep entries with no violations left, remove them:\n" + stale.joinToString("\n"))

        val unexpected = violations.filter { v -> pendingSweep.keys.none { v.startsWith("$it: ") } }
        assertTrue(
            unexpected.isEmpty(),
            "Hardcoded user-visible text in templates (bind it with th:text=\"#{key}\" / th:attr and add the key to both bundles):\n" +
                unexpected.joinToString("\n")
        )
    }

    /** True when the element or an ancestor replaces its content with a binding, or holds code. */
    private fun isBound(el: Element): Boolean {
        var cur: Element? = el
        while (cur != null) {
            if (cur.tagName() == "script" || cur.tagName() == "style") return true
            if (textBindings.any { cur!!.hasAttr(it) }) return true
            cur = cur.parent()
        }
        return false
    }

    private fun checkText(rel: String, el: Element, node: TextNode, out: MutableList<String>) {
        val text = visible(node.wholeText)
        if (text.isEmpty() || allowedText.containsKey("$rel: $text")) return
        out.add("$rel: <${el.tagName()}> \"$text\"")
    }

    private fun checkAttr(rel: String, el: Element, attr: String, out: MutableList<String>) {
        val value = visible(el.attr(attr))
        if (value.isEmpty() || allowedText.containsKey("$rel: $value")) return
        val bound = el.hasAttr("th:$attr") ||
            (el.hasAttr("th:attr") && Regex("""\b$attr\s*=""").containsMatchIn(el.attr("th:attr"))) ||
            el.hasAttr("th:attrappend") || el.hasAttr("th:attrprepend")
        if (!bound) out.add("$rel: <${el.tagName()} $attr=\"$value\">")
    }

    /** Text with inline expressions and non-letter content removed; empty when nothing is readable. */
    private fun visible(raw: String): String {
        val stripped = raw
            .replace(Regex("""\[\[.*?]]|\[\(.*?\)]""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (stripped.any { it.isLetter() }) stripped else ""
    }
}
