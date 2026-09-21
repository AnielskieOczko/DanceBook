package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards a Thymeleaf trap that no rendering test will catch unless it happens to render the
 * one page that steps on it.
 *
 * Thymeleaf evaluates *fragment expressions* — the `~{...}` in `th:replace`, `th:insert` and
 * `th:include` — in **restricted mode**, which forbids object instantiation, static class
 * access and request-parameter access. The check runs over the SpEL expression text rather
 * than the parsed syntax tree, so it cannot tell `new` the operator from `new` the English
 * word: a fragment parameter reading `${cond ? 'Add a new material' : 'Edit'}` throws
 * `TemplateProcessingException` at render time, with a message about instantiating objects
 * that says nothing about the real cause.
 *
 * It is invisible until the page is requested. The template parses, the application starts,
 * the build passes, and the page 500s the first time a user opens it — which is how
 * `materials/form.html` shipped broken and stayed that way across two releases.
 *
 * The rule, established by probing the running template engine:
 *
 * | Inside a `${...}` fragment parameter | Result   |
 * | ------------------------------------ | -------- |
 * | `'Add a new thing'`                   | throws   |
 * | `'/materials/new'`                    | fine     |
 * | `'New Thing'`                         | fine     |
 * | `'renewal process'`                   | fine     |
 *
 * So the match is the lowercase token `new` followed by whitespace, and it only bites inside
 * a `${...}` expression — a plain quoted literal parameter never reaches the SpEL evaluator.
 *
 * The fix at a call site is to compute the string in a `th:with` on an enclosing element,
 * which is evaluated unrestricted, and pass the resulting variable into the fragment.
 */
class ThymeleafRestrictedExpressionTest {

    private val templateRoot = File("src/main/resources/templates")

    /** `th:replace` / `th:insert` / `th:include` attribute values. */
    private val fragmentAttribute =
        Regex("""th:(?:replace|insert|include)\s*=\s*"([^"]*)"""")

    /** `${...}` expressions nested inside one of those attribute values. */
    private val spelExpression = Regex("""\$\{([^}]*)}""")

    /** What restricted mode rejects: `new ` the operator, `T(` static access, `param` access. */
    private val restrictedToken = Regex("""\bnew\s|\bT\s*\(|\bparam\b""")

    @Test
    fun `no fragment expression contains a token restricted mode rejects`() {
        assertTrue(templateRoot.isDirectory, "templates directory not found at $templateRoot")

        val offenders = templateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .flatMap { file ->
                file.readLines().asSequence().withIndex().flatMap { (index, line) ->
                    fragmentAttribute.findAll(line)
                        .flatMap { spelExpression.findAll(it.groupValues[1]) }
                        .filter { restrictedToken.containsMatchIn(it.groupValues[1]) }
                        .map { "${file.relativeTo(templateRoot)}:${index + 1}  \${${it.groupValues[1]}}" }
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "These fragment expressions will throw TemplateProcessingException when their page is " +
                "rendered, because Thymeleaf evaluates fragment expressions in restricted mode and " +
                "matches the token textually. Move the string into a th:with on an enclosing " +
                "element and pass the variable into the fragment.\n\n" +
                offenders.joinToString("\n") { "  $it" }
        )
    }
}
