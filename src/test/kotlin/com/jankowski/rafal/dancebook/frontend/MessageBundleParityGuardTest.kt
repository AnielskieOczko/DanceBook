package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Guard test (#247, part of #204): every message key must exist in both the English and the
 * Polish bundle, so a page can never render a raw key or silently fall back to English.
 */
class MessageBundleParityGuardTest {

    private val resources = File("src/main/resources")

    private fun keys(name: String): Set<String> {
        val file = File(resources, name)
        assertTrue(file.isFile, "$name not found")
        val props = Properties()
        file.reader(Charsets.UTF_8).use { props.load(it) }
        return props.stringPropertyNames()
    }

    @Test
    fun `every key in messages properties also exists in messages_pl properties`() {
        val missing = (keys("messages.properties") - keys("messages_pl.properties")).sorted()
        assertTrue(
            missing.isEmpty(),
            "Keys present in messages.properties but missing from messages_pl.properties:\n" +
                missing.joinToString("\n")
        )
    }

    @Test
    fun `every key in messages_pl properties also exists in messages properties`() {
        val missing = (keys("messages_pl.properties") - keys("messages.properties")).sorted()
        assertTrue(
            missing.isEmpty(),
            "Keys present in messages_pl.properties but missing from messages.properties:\n" +
                missing.joinToString("\n")
        )
    }

    @Test
    fun `neither bundle declares a key twice`() {
        val duplicates = listOf("messages.properties", "messages_pl.properties").flatMap { name ->
            File(resources, name).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && it.contains('=') }
                .map { it.substringBefore('=').trim() }
                .groupingBy { it }.eachCount()
                .filter { it.value > 1 }
                .keys.map { "$name: $it" }
        }
        assertTrue(
            duplicates.isEmpty(),
            "Duplicate keys (the last one silently wins):\n" + duplicates.joinToString("\n")
        )
    }
}
