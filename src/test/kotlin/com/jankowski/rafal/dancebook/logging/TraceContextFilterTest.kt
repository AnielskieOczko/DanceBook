package com.jankowski.rafal.dancebook.logging

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TraceContextFilterTest {

    private val filter = TraceContextFilter()

    @Test
    fun `extracts traceId and spanId from X-Cloud-Trace-Context header`() {
        val request = MockHttpServletRequest()
        request.addHeader(TraceContextFilter.CLOUD_TRACE_HEADER, "105445aa7843bc8bf206b120001000/42;o=1")
        val response = MockHttpServletResponse()

        var capturedTraceId: String? = null
        var capturedSpanId: String? = null
        var capturedRequestId: String? = null

        val filterChain = FilterChain { _, _ ->
            capturedTraceId = MDC.get(TraceContextFilter.MDC_TRACE_ID)
            capturedSpanId = MDC.get(TraceContextFilter.MDC_SPAN_ID)
            capturedRequestId = MDC.get(TraceContextFilter.MDC_REQUEST_ID)
        }

        filter.doFilter(request, response, filterChain)

        assertEquals("105445aa7843bc8bf206b120001000", capturedTraceId)
        assertEquals("42", capturedSpanId)
        assertEquals("105445aa7843bc8bf206b120001000", capturedRequestId)
        assertEquals("105445aa7843bc8bf206b120001000", response.getHeader(TraceContextFilter.REQUEST_ID_HEADER))

        // MDC must be cleaned up after request completes
        assertNull(MDC.get(TraceContextFilter.MDC_TRACE_ID))
        assertNull(MDC.get(TraceContextFilter.MDC_SPAN_ID))
        assertNull(MDC.get(TraceContextFilter.MDC_REQUEST_ID))
    }

    @Test
    fun `falls back to X-Request-ID when X-Cloud-Trace-Context is absent`() {
        val request = MockHttpServletRequest()
        request.addHeader(TraceContextFilter.REQUEST_ID_HEADER, "client-req-999")
        val response = MockHttpServletResponse()

        var capturedTraceId: String? = null
        var capturedSpanId: String? = null

        val filterChain = FilterChain { _, _ ->
            capturedTraceId = MDC.get(TraceContextFilter.MDC_TRACE_ID)
            capturedSpanId = MDC.get(TraceContextFilter.MDC_SPAN_ID)
        }

        filter.doFilter(request, response, filterChain)

        assertEquals("client-req-999", capturedTraceId)
        assertNull(capturedSpanId)
        assertEquals("client-req-999", response.getHeader(TraceContextFilter.REQUEST_ID_HEADER))
        assertNull(MDC.get(TraceContextFilter.MDC_TRACE_ID))
    }

    @Test
    fun `generates random UUID when no trace or request headers are present`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        var capturedTraceId: String? = null

        val filterChain = FilterChain { _, _ ->
            capturedTraceId = MDC.get(TraceContextFilter.MDC_TRACE_ID)
        }

        filter.doFilter(request, response, filterChain)

        assertNotNull(capturedTraceId)
        assertTrue(capturedTraceId!!.length >= 32)
        assertEquals(capturedTraceId, response.getHeader(TraceContextFilter.REQUEST_ID_HEADER))
        assertNull(MDC.get(TraceContextFilter.MDC_TRACE_ID))
    }
}
