package com.jankowski.rafal.dancebook.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.logging.structured.StructuredLogFormatter
import java.time.Instant

/**
 * Formats Logback events as single-line JSON entries optimized for Google Cloud Logging
 * and Cloud Error Reporting on Cloud Run.
 *
 * Cloud Logging recognizes the severity field, while Cloud Error Reporting detects and
 * groups exceptions when the full stack trace is included in the message field.
 */
class CloudRunStructuredLogFormatter : StructuredLogFormatter<ILoggingEvent> {

    private val objectMapper = ObjectMapper()
    private val gcpProjectId: String? = (
        System.getenv("GOOGLE_CLOUD_PROJECT")
            ?: System.getenv("GCP_PROJECT_ID")
            ?: System.getProperty("google.cloud.project")
    )?.trim()?.takeIf { it.isNotEmpty() }

    override fun format(event: ILoggingEvent): String {
        val payload = LinkedHashMap<String, Any?>()

        // 1. Cloud Logging Severity
        payload["severity"] = mapSeverity(event.level)

        // 2. Timestamp (ISO-8601)
        payload["timestamp"] = Instant.ofEpochMilli(event.timeStamp).toString()

        // 3. Logger and Thread
        payload["logger"] = event.loggerName
        payload["thread"] = event.threadName

        // 4. Trace & Request identifiers
        val mdc = try {
            event.mdcPropertyMap ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        val traceId = mdc["traceId"] ?: mdc["requestId"]
        val spanId = mdc["spanId"]

        if (!traceId.isNullOrBlank()) {
            val fullTrace = if (!gcpProjectId.isNullOrBlank()) {
                "projects/$gcpProjectId/traces/$traceId"
            } else {
                traceId
            }
            payload["logging.googleapis.com/trace"] = fullTrace
            payload["traceId"] = traceId
            payload["requestId"] = traceId
        }

        if (!spanId.isNullOrBlank()) {
            payload["logging.googleapis.com/spanId"] = spanId
        }

        // 5. Message and Stack Trace
        // Cloud Error Reporting parses Java exceptions and stack traces from the `message` field.
        val formattedMsg = event.formattedMessage ?: ""
        val stackTrace = event.throwableProxy?.let { ThrowableProxyUtil.asString(it) }

        if (!stackTrace.isNullOrBlank()) {
            payload["stack_trace"] = stackTrace
            payload["message"] = if (formattedMsg.isNotBlank()) {
                "$formattedMsg\n$stackTrace"
            } else {
                stackTrace
            }
        } else {
            payload["message"] = formattedMsg
        }

        // 6. Additional MDC context if present
        val extraContext = mdc.filterKeys { it !in KNOWN_MDC_KEYS }
        if (extraContext.isNotEmpty()) {
            payload["context"] = extraContext
        }

        return objectMapper.writeValueAsString(payload) + "\n"
    }

    private fun mapSeverity(level: Level?): String {
        return when (level?.toInt()) {
            Level.ERROR_INT -> "ERROR"
            Level.WARN_INT -> "WARNING"
            Level.INFO_INT -> "INFO"
            Level.DEBUG_INT -> "DEBUG"
            Level.TRACE_INT -> "DEBUG"
            else -> "DEFAULT"
        }
    }

    companion object {
        private val KNOWN_MDC_KEYS = setOf("traceId", "requestId", "spanId")
    }
}
