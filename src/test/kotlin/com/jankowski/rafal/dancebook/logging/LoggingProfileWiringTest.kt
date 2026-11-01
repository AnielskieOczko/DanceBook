package com.jankowski.rafal.dancebook.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils

/**
 * Structured JSON logging is switched on by the `prod` profile only (#442).
 * This reads the property files directly: starting a context and re-initialising the global
 * logging system hung the CI test task, and the formatter itself is covered by
 * [CloudRunStructuredLogFormatterTest].
 */
class LoggingProfileWiringTest {

    private val key = "logging.structured.format.console"

    @Test
    fun `prod profile selects the Cloud Run structured log formatter`() {
        val prod = PropertiesLoaderUtils.loadProperties(ClassPathResource("application-prod.properties"))

        assertEquals(CloudRunStructuredLogFormatter::class.java.name, prod.getProperty(key))
        assertNotNull(Class.forName(prod.getProperty(key)))
    }

    @Test
    fun `default profile keeps plain text logs`() {
        val default = PropertiesLoaderUtils.loadProperties(ClassPathResource("application.properties"))

        assertNull(default.getProperty(key), "the structured format must only be set by the prod profile")
    }
}
