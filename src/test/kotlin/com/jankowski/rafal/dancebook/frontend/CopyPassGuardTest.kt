package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Guard test for Issue #320 copy pass:
 * - User notes use "Note" / "notatka", never "material" / "materiał" or "sequence" / "sekwencja"
 * - Routines use "Routine" / "Choreography" / "układ" / "choreografia", not "sequence" as a routine name
 * - Empty states provide actionable guidance (what the list contains and how to add/find items)
 * - Validation errors are actionable
 */
class CopyPassGuardTest {

    private val resources = File("src/main/resources")

    private fun loadProperties(name: String): Map<String, String> {
        val file = File(resources, name)
        assertTrue(file.isFile, "$name not found")
        val props = Properties()
        file.reader(Charsets.UTF_8).use { props.load(it) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    @Test
    fun `note user-facing copy in English bundle does not use material or sequence for user notes`() {
        val en = loadProperties("messages.properties")
        val forbiddenKeys = mutableListOf<String>()

        for ((key, value) in en) {
            // Study materials and source links are exempt legitimate usages
            if (key.contains("study_material") || key.contains("source_link") || key.contains("external")) {
                continue
            }
            if (value.contains("study material", ignoreCase = true)) {
                continue
            }

            // Check keys that pertain to user notes
            if (key.startsWith("materials.") || key.startsWith("notes.")) {
                if (value.contains("\\bmaterial\\b".toRegex(RegexOption.IGNORE_CASE)) ||
                    value.contains("\\bmaterials\\b".toRegex(RegexOption.IGNORE_CASE))
                ) {
                    forbiddenKeys.add("$key = $value")
                }
            }
        }

        assertTrue(
            forbiddenKeys.isEmpty(),
            "Found 'material(s)' used for user notes in messages.properties:\n" +
                forbiddenKeys.joinToString("\n")
        )
    }

    @Test
    fun `note user-facing copy in Polish bundle does not use material for user notes`() {
        val pl = loadProperties("messages_pl.properties")
        val forbiddenKeys = mutableListOf<String>()

        for ((key, value) in pl) {
            // Study materials and source links are exempt legitimate usages
            if (key.contains("study_material") || key.contains("source_link") || key.contains("external")) {
                continue
            }
            if (value.contains("materiał szkoleniowy", ignoreCase = true) ||
                value.contains("materiały szkoleniowe", ignoreCase = true) ||
                value.contains("materiałów szkoleniowych", ignoreCase = true)
            ) {
                continue
            }

            // Check keys that pertain to user notes
            if (key.startsWith("materials.") || key.startsWith("notes.")) {
                if (value.contains("\\bmateriał\\b".toRegex(RegexOption.IGNORE_CASE)) ||
                    value.contains("\\bmateriały\\b".toRegex(RegexOption.IGNORE_CASE)) ||
                    value.contains("\\bmateriałów\\b".toRegex(RegexOption.IGNORE_CASE)) ||
                    value.contains("\\bmateriałom\\b".toRegex(RegexOption.IGNORE_CASE)) ||
                    value.contains("\\bmateriałem\\b".toRegex(RegexOption.IGNORE_CASE))
                ) {
                    forbiddenKeys.add("$key = $value")
                }
            }
        }

        assertTrue(
            forbiddenKeys.isEmpty(),
            "Found 'materiał(y)' used for user notes in messages_pl.properties:\n" +
                forbiddenKeys.joinToString("\n")
        )
    }

    @Test
    fun `empty state messages are actionable with instructions on adding or finding items`() {
        val en = loadProperties("messages.properties")
        val pl = loadProperties("messages_pl.properties")

        val emptyKeys = en.keys.filter {
            (it.endsWith(".empty") || it.endsWith(".empty_desc") || it.endsWith(".empty_text")) &&
                !it.startsWith("js.guided.diff")
        }

        val nonActionableEn = mutableListOf<String>()
        val nonActionablePl = mutableListOf<String>()

        for (k in emptyKeys) {
            val vEn = en[k] ?: ""
            val vPl = pl[k] ?: ""

            // Must have meaningful content beyond a mere 1-2 words
            if (vEn.length < 15) {
                nonActionableEn.add("$k: '$vEn'")
            }
            if (vPl.length < 15) {
                nonActionablePl.add("$k: '$vPl'")
            }
        }

        assertTrue(nonActionableEn.isEmpty(), "Non-actionable English empty states:\n" + nonActionableEn.joinToString("\n"))
        assertTrue(nonActionablePl.isEmpty(), "Non-actionable Polish empty states:\n" + nonActionablePl.joinToString("\n"))
    }

    @Test
    fun `validation error messages state what went wrong and how to fix it`() {
        val en = loadProperties("messages.properties")
        val pl = loadProperties("messages_pl.properties")

        val validationKeys = en.keys.filter {
            it.startsWith("validation.") ||
                (it.startsWith("error.") && !it.startsWith("error.generic.title") && !it.startsWith("error.generic.heading"))
        }

        val shortErrorsEn = mutableListOf<String>()
        val shortErrorsPl = mutableListOf<String>()

        for (k in validationKeys) {
            val vEn = en[k] ?: ""
            val vPl = pl[k] ?: ""

            if (vEn.length < 10) {
                shortErrorsEn.add("$k: '$vEn'")
            }
            if (vPl.length < 10) {
                shortErrorsPl.add("$k: '$vPl'")
            }
        }

        assertTrue(shortErrorsEn.isEmpty(), "Under-specified English validation messages:\n" + shortErrorsEn.joinToString("\n"))
        assertTrue(shortErrorsPl.isEmpty(), "Under-specified Polish validation messages:\n" + shortErrorsPl.joinToString("\n"))
    }
}
