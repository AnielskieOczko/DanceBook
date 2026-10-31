package com.jankowski.rafal.dancebook.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudRunStructuredLogFormatterTest {

    private val formatter = CloudRunStructuredLogFormatter()
    private val objectMapper = ObjectMapper()

    @Test
    fun `formats standard log event as single line json with Cloud Logging fields`() {
        val event = LoggingEvent().apply {
            level = Level.INFO
            message = "Application started successfully"
            loggerName = "com.jankowski.rafal.dancebook.DanceBookApplication"
            threadName = "main"
            timeStamp = 1778100000000L
        }

        val output = formatter.format(event)

        // Must end with exactly one newline, and contain no literal internal newlines
        assertTrue(output.endsWith("\n"), "Formatted log must end with a newline")
        val singleLine = output.trimEnd('\n')
        assertFalse(singleLine.contains("\n"), "Log entry must be exactly one JSON line")

        val rootNode = objectMapper.readTree(singleLine)
        assertEquals("INFO", rootNode["severity"].asText())
        assertEquals("Application started successfully", rootNode["message"].asText())
        assertEquals("com.jankowski.rafal.dancebook.DanceBookApplication", rootNode["logger"].asText())
        assertEquals("main", rootNode["thread"].asText())
        assertNotNull(rootNode["timestamp"])
    }

    @Test
    fun `formats logged exception as exactly one JSON line containing severity message and full stack trace`() {
        val exception = IllegalStateException("Database pool exhausted", RuntimeException("Root cause"))
        val event = LoggingEvent().apply {
            level = Level.ERROR
            message = "Failed to obtain database connection"
            loggerName = "com.jankowski.rafal.dancebook.repository.MaterialRepository"
            threadName = "http-nio-8080-exec-1"
            timeStamp = 1778100000000L
            setThrowableProxy(ThrowableProxy(exception))
        }

        val output = formatter.format(event)

        // Strict single-line check
        assertTrue(output.endsWith("\n"))
        val singleLine = output.trimEnd('\n')
        assertFalse(singleLine.contains("\n"), "Logged exception must be strictly one JSON line")

        val rootNode = objectMapper.readTree(singleLine)
        assertEquals("ERROR", rootNode["severity"].asText())

        // message must contain the formatted message and the stack trace for Cloud Error Reporting
        val message = rootNode["message"].asText()
        assertTrue(message.contains("Failed to obtain database connection"), "Message must contain log message")
        assertTrue(message.contains("IllegalStateException: Database pool exhausted"), "Message must contain exception")
        assertTrue(message.contains("Caused by: java.lang.RuntimeException: Root cause"), "Message must contain cause")

        // stack_trace field must also contain the complete stack trace
        val stackTrace = rootNode["stack_trace"].asText()
        assertTrue(stackTrace.contains("IllegalStateException: Database pool exhausted"))
        assertTrue(stackTrace.contains("Caused by: java.lang.RuntimeException: Root cause"))
    }

    @Test
    fun `maps logback levels to Cloud Logging severities correctly`() {
        fun formatWithLevel(level: Level): String {
            val event = LoggingEvent().apply {
                this.level = level
                this.message = "test"
            }
            val line = formatter.format(event).trimEnd('\n')
            return objectMapper.readTree(line)["severity"].asText()
        }

        assertEquals("ERROR", formatWithLevel(Level.ERROR))
        assertEquals("WARNING", formatWithLevel(Level.WARN))
        assertEquals("INFO", formatWithLevel(Level.INFO))
        assertEquals("DEBUG", formatWithLevel(Level.DEBUG))
        assertEquals("DEBUG", formatWithLevel(Level.TRACE))
    }

    @Test
    fun `includes traceId requestId and Cloud Logging trace URI from MDC`() {
        val event = LoggingEvent().apply {
            level = Level.INFO
            message = "Handling HTTP request"
            mdcPropertyMap = mapOf(
                "traceId" to "105445aa7843bc8bf206b120001000",
                "spanId" to "42",
                "customKey" to "customValue"
            )
        }

        val line = formatter.format(event).trimEnd('\n')
        val rootNode = objectMapper.readTree(line)

        assertEquals("105445aa7843bc8bf206b120001000", rootNode["traceId"].asText())
        assertEquals("105445aa7843bc8bf206b120001000", rootNode["requestId"].asText())
        assertNotNull(rootNode["logging.googleapis.com/trace"])
        assertTrue(rootNode["logging.googleapis.com/trace"].asText().contains("105445aa7843bc8bf206b120001000"))
        assertEquals("42", rootNode["logging.googleapis.com/spanId"].asText())
        assertEquals("customValue", rootNode["context"]["customKey"].asText())
    }
}
