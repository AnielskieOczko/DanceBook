package com.jankowski.rafal.dancebook.controller.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * TEMPORARY debug endpoint to verify Cloud Run proxy headers.
 * Remove after confirming ForwardedHeaderFilter works.
 */
@RestController
class DebugHeadersController {

    private val log = LoggerFactory.getLogger(DebugHeadersController::class.java)

    @GetMapping("/debug/headers")
    fun debugHeaders(request: HttpServletRequest): Map<String, Any?> {
        val headers = mutableMapOf<String, String>()
        request.headerNames.asIterator().forEach { name ->
            headers[name] = request.getHeader(name)
        }

        val result = mapOf(
            "scheme" to request.scheme,
            "isSecure" to request.isSecure,
            "remoteAddr" to request.remoteAddr,
            "serverName" to request.serverName,
            "requestURL" to request.requestURL.toString(),
            "headers" to headers
        )

        log.info("Debug headers: {}", result)
        return result
    }
}
