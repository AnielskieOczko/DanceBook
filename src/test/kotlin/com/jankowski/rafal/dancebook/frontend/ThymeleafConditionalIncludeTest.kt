package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against a subtle Thymeleaf precedence bug where a conditional (`th:if` or `th:unless`)
 * is placed on the same element as a fragment inclusion (`th:replace`, `th:insert`, or `th:include`).
 *
 * Thymeleaf processes fragment inclusion at attribute precedence 100, whereas conditionals
 * are evaluated at precedence 300. As a result, the fragment inclusion executes first and replaces
 * or inserts the host element before the condition is ever evaluated, silently dropping the
 * condition attached to it. The inclusion becomes unconditional.
 *
 * When the conditionally included fragment is an icon with a null name, it renders an empty `<span>`
 * with no glyph, surviving builds and remaining visually invisible while emitting stray DOM elements.
 * When applied to visible components (such as alerts or empty-state panels), the component renders
 * even when the condition is false.
 *
 * The correct pattern is to wrap the fragment inclusion in an enclosing `<th:block th:if="...">`:
 * ```html
 * <th:block th:if="${icon != null}">
 *     <span th:replace="~{fragments/icon :: icon(name=${icon})}"></span>
 * </th:block>
 * ```
 */
class ThymeleafConditionalIncludeTest {

    private val templateRoot = File("src/main/resources/templates")

    // Matches opening tags <tagName attributes...> handling quotes across multiple lines
    private val openingTagRegex = Regex("""<([a-zA-Z0-9:-]+)((?:'[^']*'|"[^"]*"|[^>'"])*)>""", RegexOption.DOT_MATCHES_ALL)
    private val conditionalAttributeRegex = Regex("""\bth:(?:if|unless)\s*=""", RegexOption.IGNORE_CASE)
    private val inclusionAttributeRegex = Regex("""\bth:(?:replace|insert|include)\s*=""", RegexOption.IGNORE_CASE)

    @Test
    fun `no template pairs a conditional with a fragment inclusion on the same element`() {
        assertTrue(templateRoot.isDirectory, "Templates directory not found at $templateRoot")

        val offenders = templateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .flatMap { file ->
                val content = file.readText()
                openingTagRegex.findAll(content)
                    .filter { match ->
                        val attrs = match.groupValues[2]
                        conditionalAttributeRegex.containsMatchIn(attrs) &&
                            inclusionAttributeRegex.containsMatchIn(attrs)
                    }
                    .map { match ->
                        val lineNumber = content.substring(0, match.range.first).count { it == '\n' } + 1
                        val firstLine = match.value.lines().first().trim()
                        "${file.relativeTo(templateRoot)}:$lineNumber  $firstLine"
                    }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Found templates pairing a conditional (th:if / th:unless) with a fragment inclusion " +
                "(th:replace / th:insert / th:include) on the same element.\n\n" +
                "Why this breaks:\n" +
                "Thymeleaf evaluates fragment inclusions at attribute precedence 100 and conditionals " +
                "at precedence 300. The inclusion executes first, replacing the host element and silently " +
                "dropping the condition before it is ever evaluated. The inclusion is unconditional.\n\n" +
                "Fix:\n" +
                "Put the conditional on an enclosing <th:block> instead:\n" +
                "  <th:block th:if=\"\${condition}\">\n" +
                "      <span th:replace=\"~{fragments/...}\"></span>\n" +
                "  </th:block>\n\n" +
                "Offenders:\n" +
                offenders.joinToString("\n") { "  $it" }
        )
    }
}
