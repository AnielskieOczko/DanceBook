package com.jankowski.rafal.dancebook.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Extracts Cloud Run trace context or request identifiers from incoming HTTP requests
 * and populates SLF4J MDC so log entries can be correlated per request.
 *
 * Cloud Run forwards trace context via the `X-Cloud-Trace-Context` header with format:
 * `TRACE_ID/SPAN_ID;o=TRACE_TRUE`.
 *
 * Falls back to `X-Request-ID` or generates a random 32-character hex ID.
 */
class TraceContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val cloudTraceHeader = request.getHeader(CLOUD_TRACE_HEADER)
        val (traceId, spanId) = if (!cloudTraceHeader.isNullOrBlank()) {
            parseCloudTraceHeader(cloudTraceHeader)
        } else {
            val reqId = request.getHeader(REQUEST_ID_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().replace("-", "")
            reqId to null
        }

        MDC.put(MDC_TRACE_ID, traceId)
        MDC.put(MDC_REQUEST_ID, traceId)
        if (spanId != null) {
            MDC.put(MDC_SPAN_ID, spanId)
        }

        response.setHeader(REQUEST_ID_HEADER, traceId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_TRACE_ID)
            MDC.remove(MDC_REQUEST_ID)
            if (spanId != null) {
                MDC.remove(MDC_SPAN_ID)
            }
        }
    }

    private fun parseCloudTraceHeader(header: String): Pair<String, String?> {
        val slashIndex = header.indexOf('/')
        val semiIndex = header.indexOf(';')

        val traceId = when {
            slashIndex != -1 -> header.substring(0, slashIndex).trim()
            semiIndex != -1 -> header.substring(0, semiIndex).trim()
            else -> header.trim()
        }

        val spanId = if (slashIndex != -1) {
            val end = if (semiIndex != -1 && semiIndex > slashIndex) semiIndex else header.length
            header.substring(slashIndex + 1, end).trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }

        return traceId to spanId
    }

    companion object {
        const val CLOUD_TRACE_HEADER = "X-Cloud-Trace-Context"
        const val REQUEST_ID_HEADER = "X-Request-ID"

        const val MDC_TRACE_ID = "traceId"
        const val MDC_REQUEST_ID = "requestId"
        const val MDC_SPAN_ID = "spanId"
    }
}
