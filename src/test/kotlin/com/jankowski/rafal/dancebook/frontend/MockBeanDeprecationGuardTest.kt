package com.jankowski.rafal.dancebook.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against the use of deprecated `@MockBean` and `@SpyBean` annotations in test sources.
 *
 * Spring Boot 3.4+ / 3.5+ deprecates `org.springframework.boot.test.mock.mockito.MockBean` and
 * `SpyBean` in favour of Spring Framework's `@MockitoBean` and `@MockitoSpyBean`
 * (`org.springframework.test.context.bean.override.mockito.*`).
 *
 * Issue #277: All test sources must use `@MockitoBean` / `@MockitoSpyBean` to eliminate
 * deprecation compiler warnings and prevent breakage in future major Spring Boot releases.
 */
class MockBeanDeprecationGuardTest {

    private val testDir = File("src/test/kotlin")

    @Test
    fun `no test file imports or uses deprecated MockBean or SpyBean`() {
        assertTrue(testDir.isDirectory, "Test directory not found at $testDir")

        val offendingFiles = mutableListOf<String>()

        testDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MockBeanDeprecationGuardTest.kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import org.springframework.boot.test.mock.mockito.MockBean") ||
                        trimmed.startsWith("import org.springframework.boot.test.mock.mockito.SpyBean") ||
                        trimmed.contains("@MockBean") ||
                        trimmed.contains("@SpyBean")
                    ) {
                        offendingFiles.add("${file.relativeTo(File("."))}:${index + 1}: $trimmed")
                    }
                }
            }

        assertTrue(
            offendingFiles.isEmpty(),
            "Found deprecated @MockBean or @SpyBean usage in test files. Use Spring Framework's " +
                "@MockitoBean / @MockitoSpyBean (org.springframework.test.context.bean.override.mockito.*) instead:\n" +
                offendingFiles.joinToString("\n")
        )
    }
}
