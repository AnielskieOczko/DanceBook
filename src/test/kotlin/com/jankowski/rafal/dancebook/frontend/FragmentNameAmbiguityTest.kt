package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against fragment name collision with HTML elements in the Thymeleaf fragment catalog.
 *
 * Thymeleaf fragment expressions (~{template :: selector}) use markup selectors rather than pure
 * fragment name lookups. A bare name in a selector matches any element carrying that th:fragment
 * attribute OR any element whose tag name equals that selector.
 *
 * If a catalog fragment is named after an HTML element (e.g. `textarea`, `select`, or `button`),
 * any caller referencing ~{fragments/form :: textarea} will match the fragment itself PLUS every
 * literal <textarea> tag elsewhere in the file. This causes duplicate rendered elements and
 * silent data corruption (e.g. multiple inputs submitted with the same field name).
 *
 * By policy, fragments in templates/fragments/ must never share a name with HTML elements.
 */
class FragmentNameAmbiguityTest {

    private val fragmentsDir = File("src/main/resources/templates/fragments")

    // Standard HTML element tag names that must never be used as fragment names
    // to prevent selector collision with literal tags in the catalog files.
    private val forbiddenHtmlTagNames = setOf(
        // Document metadata & structural
        "header", "footer", "nav", "section", "article", "aside", "main",
        "h1", "h2", "h3", "h4", "h5", "h6", "address",
        "link", "style", "meta", "title", "base", "head", "body", "html",
        // Grouping & text
        "p", "div", "span", "hr", "pre", "blockquote", "ol", "ul", "li", "dl", "dt", "dd",
        "figure", "figcaption", "a", "em", "strong", "small", "s", "cite", "q",
        "dfn", "abbr", "ruby", "rt", "rp", "data", "time", "code", "var", "samp",
        "kbd", "sub", "sup", "i", "b", "u", "mark", "bdi", "bdo", "br", "wbr",
        // Embedded & media
        "img", "iframe", "embed", "object", "param", "video", "audio", "source", "track", "canvas", "map", "area", "svg", "math",
        // Tables
        "table", "caption", "colgroup", "col", "tbody", "thead", "tfoot", "tr", "td", "th",
        // Forms & interactive
        "form", "label", "input", "button", "select", "datalist", "optgroup", "option",
        "textarea", "output", "progress", "meter", "fieldset", "legend",
        "details", "summary", "dialog", "menu", "slot", "template"
    )

    private val fragmentPattern = Regex("""\bth:fragment\s*=\s*["']([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)

    @Test
    fun `no fragment in the catalog is named after an HTML element`() {
        assertTrue(fragmentsDir.isDirectory, "Catalog directory not found at $fragmentsDir")

        val violations = mutableListOf<String>()

        fragmentsDir.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        for (match in fragmentPattern.findAll(line)) {
                            val fragmentName = match.groupValues[1]
                            if (forbiddenHtmlTagNames.contains(fragmentName.lowercase())) {
                                violations.add(
                                    "${file.name}:${index + 1} declares th:fragment=\"$fragmentName\". " +
                                        "Catalog fragments must not be named after HTML element tags (<$fragmentName>). " +
                                        "Thymeleaf markup selectors resolve '~{... :: $fragmentName}' as both " +
                                        "[th:fragment='$fragmentName'] and any literal <$fragmentName> in that file, " +
                                        "causing duplicate rendering and form submission data corruption. " +
                                        "Rename the fragment (e.g. '${fragmentName}Field' or similar)."
                                )
                            }
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Found catalog fragments named after HTML elements:\n" +
                violations.joinToString("\n")
        )
    }
}
