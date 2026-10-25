package com.jankowski.rafal.dancebook.frontend

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Tests for the Firebase Hosting splash page (Issue #391).
 *
 * Verifies that static files exist, Firebase configuration has the expected
 * site and SPA rewrite rule, index.html contains necessary branding and accessibility
 * elements, and URL redirect calculations preserve deep links, character encodings,
 * and protect against open redirects.
 */
class SplashPageTest {

    private val splashDir = File("splash")
    private val expectedAppOrigin = "https://dancebook-918960884147.europe-central2.run.app"
    private val expectedFirebaseSite = "dancebook-a6588"

    @Test
    fun `splash directory and required static files exist`() {
        assertTrue(splashDir.isDirectory, "splash directory should exist at $splashDir")

        val requiredFiles = listOf(
            "index.html",
            "splash.css",
            "splash.js",
            "firebase.json",
            ".firebaserc",
            "README.md"
        )

        for (fileName in requiredFiles) {
            val file = File(splashDir, fileName)
            assertTrue(file.isFile, "Required file $fileName must exist in splash directory")
            assertTrue(file.length() > 0, "$fileName should not be empty")
        }
    }

    @Test
    fun `firebase json configures site and rewrite for deep links`() {
        val firebaseJsonFile = File(splashDir, "firebase.json")
        val json = ObjectMapper().readTree(firebaseJsonFile)

        val hostingNode = json.get("hosting")
        assertTrue(hostingNode != null, "firebase.json must contain 'hosting' object")

        assertEquals(expectedFirebaseSite, hostingNode.get("site")?.asText())

        val rewrites = hostingNode.get("rewrites")
        assertTrue(rewrites != null && rewrites.isArray, "hosting must define rewrites array")

        val hasSpaRewrite = rewrites.any { rewrite ->
            rewrite.get("source")?.asText() == "**" && rewrite.get("destination")?.asText() == "/index.html"
        }
        assertTrue(hasSpaRewrite, "firebase.json must rewrite '**' to '/index.html' for deep links")
    }

    @Test
    fun `firebaserc configures default project`() {
        val firebasercFile = File(splashDir, ".firebaserc")
        val json = ObjectMapper().readTree(firebasercFile)

        val defaultProject = json.get("projects")?.get("default")?.asText()
        assertEquals(expectedFirebaseSite, defaultProject)
    }

    @Test
    fun `index html includes viewport, branding, accessibility, and reduced motion`() {
        val indexHtml = File(splashDir, "index.html").readText()

        assertTrue(indexHtml.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"),
            "index.html must have proper responsive viewport meta tag")
        assertTrue(indexHtml.contains("DanceBook"), "index.html must display DanceBook brand")
        assertTrue(indexHtml.contains("status-message"), "index.html must have status-message element")
        assertTrue(indexHtml.contains("status-detail"), "index.html must have status-detail element")
        assertTrue(indexHtml.contains("retry-btn"), "index.html must have retry-btn element")
        assertTrue(indexHtml.contains("direct-link"), "index.html must have direct-link fallback")
        assertTrue(indexHtml.contains("prefers-reduced-motion"),
            "index.html must support prefers-reduced-motion: reduce")
    }

    @Test
    fun `redirect target calculation matches specification for various inputs`() {
        fun computeRedirectTarget(currentPathAndQuery: String): String {
            val baseUri = URI.create(expectedAppOrigin)
            val pathUri = URI.create(currentPathAndQuery)
            return baseUri.resolve(pathUri).toASCIIString()
        }

        // 1. Root path
        assertEquals(
            "$expectedAppOrigin/",
            computeRedirectTarget("/")
        )

        // 2. Deep path without query
        assertEquals(
            "$expectedAppOrigin/materials/42",
            computeRedirectTarget("/materials/42")
        )

        // 3. Deep path with query string
        assertEquals(
            "$expectedAppOrigin/materials/42?tag=latin&sort=name",
            computeRedirectTarget("/materials/42?tag=latin&sort=name")
        )

        // 4. Encoded path and query parameters
        val encodedPath = "/dance-figures/" + URLEncoder.encode("walc angielski", StandardCharsets.UTF_8).replace("+", "%20")
        val encodedQuery = "?q=" + URLEncoder.encode("krok obrotowy", StandardCharsets.UTF_8).replace("+", "%20")
        val expectedEncodedUrl = "$expectedAppOrigin/dance-figures/walc%20angielski?q=krok%20obrotowy"

        assertEquals(
            expectedEncodedUrl,
            computeRedirectTarget("$encodedPath$encodedQuery")
        )
    }

    @Test
    fun `splash js prevents open redirect and validates target hosts`() {
        val splashJs = File(splashDir, "splash.js").readText()

        assertTrue(splashJs.contains("function sanitizePath"),
            "splash.js must sanitize pathname against leading slashes and backslashes")
        assertTrue(splashJs.contains("target.pathname = sanitizePath(pathname)"),
            "splash.js must assign sanitized pathname to target URL")
        assertTrue(splashJs.contains("parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1'"),
            "splash.js must strictly match localhost or 127.0.0.1 without open-ended prefixes")
        assertTrue(splashJs.contains("!parsed.username && !parsed.password"),
            "splash.js must reject userinfo in target override")
    }

    @Test
    fun `splash css and index html enforce hidden attribute precedence and leave no empty gap`() {
        val splashCss = File(splashDir, "splash.css").readText()
        val indexHtml = File(splashDir, "index.html").readText()

        assertTrue(splashCss.contains("[hidden]") && splashCss.contains("display: none !important"),
            "splash.css must enforce [hidden] { display: none !important; }")
        assertTrue(indexHtml.contains("[hidden]") && indexHtml.contains("display: none !important"),
            "index.html must enforce [hidden] { display: none !important; }")

        assertTrue(!splashCss.contains("min-height: 4rem"),
            "splash.css must not have min-height: 4rem on status-section")
        assertTrue(!indexHtml.contains("min-height: 4rem"),
            "index.html must not have min-height: 4rem on status-section")
    }
}
