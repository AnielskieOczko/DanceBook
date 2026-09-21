package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against stored-XSS by ensuring that unescaped HTML output (`th:utext` or inlined `[(...)]`)
 * can only appear in the single sanctioned rich-text display fragment (`fragments/rich-text.html`).
 *
 * Every other template in the application must use Thymeleaf's escaping constructs (`th:text` or `[[...]]`).
 */
class UnescapedTemplateOutputTest {

    private val templateRoot = File("src/main/resources/templates")
    private val permittedFragmentRelativePath = "fragments/rich-text.html"

    private val unescapedAttributePattern = Regex("""\bth:utext\s*=""", RegexOption.IGNORE_CASE)
    private val unescapedInlinedPattern = Regex("""\[\(""")

    @Test
    fun `no template outside the rich-text fragment produces unescaped output`() {
        assertTrue(templateRoot.isDirectory, "Templates directory not found at $templateRoot")

        val permittedFile = File(templateRoot, permittedFragmentRelativePath)
        assertTrue(permittedFile.isFile, "Permitted rich-text fragment not found at $permittedFile")

        // The permitted fragment must actually use unescaped output for its content fragment,
        // ensuring the test guard remains meaningful.
        val permittedContent = permittedFile.readText()
        assertTrue(
            unescapedAttributePattern.containsMatchIn(permittedContent) || unescapedInlinedPattern.containsMatchIn(permittedContent),
            "$permittedFragmentRelativePath must be the designated unescaped rendering fragment"
        )

        val offenders = templateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .filter { it.relativeTo(templateRoot).path.replace('\\', '/') != permittedFragmentRelativePath }
            .flatMap { file ->
                file.readLines().asSequence().withIndex().filter { (_, line) ->
                    unescapedAttributePattern.containsMatchIn(line) || unescapedInlinedPattern.containsMatchIn(line)
                }.map { (index, line) ->
                    "${file.relativeTo(templateRoot)}:${index + 1}  ${line.trim()}"
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Found unescaped output outside the designated display fragment ($permittedFragmentRelativePath). " +
                "All rich text must be rendered through fragments/rich-text :: content. Offenders:\n" +
                offenders.joinToString("\n") { "  $it" }
        )
    }
}
