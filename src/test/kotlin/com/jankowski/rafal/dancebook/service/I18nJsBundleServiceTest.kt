package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

class I18nJsBundleServiceTest {

    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
    }

    private val service = I18nJsBundleServiceImpl(messageSource)

    private val polish = Locale.forLanguageTag("pl")
    private val english = Locale.ENGLISH

    @Test
    fun `service returns English messages for English locale`() {
        val map = service.getBundleMap(english)

        assertEquals("The assistant couldn't answer just now. Try again.", map["js.assistant.error"])
        assertEquals("An unexpected error occurred. Please try again.", map["js.error.unexpected"])
        assertEquals("Network error. Please check your connection and try again.", map["js.error.network"])
        assertEquals("Dismiss", map["common.dismiss"])
        assertEquals("Upload", map["notes.form.drive.upload"])
        assertEquals("Uploading...", map["notes.form.drive.uploading"])
    }

    @Test
    fun `service returns Polish messages for Polish locale`() {
        val map = service.getBundleMap(polish)

        assertEquals("Asystent nie mógł teraz odpowiedzieć. Spróbuj ponownie.", map["js.assistant.error"])
        assertEquals("Wystąpił nieoczekiwany błąd. Spróbuj ponownie.", map["js.error.unexpected"])
        assertEquals("Błąd sieci. Sprawdź połączenie i spróbuj ponownie.", map["js.error.network"])
        assertEquals("Zamknij", map["common.dismiss"])
        assertEquals("Prześlij", map["notes.form.drive.upload"])
        assertEquals("Przesyłanie...", map["notes.form.drive.uploading"])
    }

    @Test
    fun `bundle JSON is valid and non-empty for both locales`() {
        val enJson = service.getBundleJson(english)
        val plJson = service.getBundleJson(polish)

        assertTrue(enJson.startsWith("{") && enJson.endsWith("}"))
        assertTrue(plJson.startsWith("{") && plJson.endsWith("}"))

        assertTrue(enJson.contains(""""js.assistant.error":"The assistant couldn't answer just now. Try again.""""))
        assertTrue(plJson.contains(""""js.assistant.error":"Asystent nie mógł teraz odpowiedzieć. Spróbuj ponownie.""""))
    }

    @Test
    fun `1-to-1 key parity between English and Polish message bundles for JS keys`() {
        val classLoader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val enProps = Properties().apply {
            classLoader.getResourceAsStream("messages.properties")?.use {
                load(InputStreamReader(it, StandardCharsets.UTF_8))
            }
        }
        val plProps = Properties().apply {
            classLoader.getResourceAsStream("messages_pl.properties")?.use {
                load(InputStreamReader(it, StandardCharsets.UTF_8))
            }
        }

        val enJsKeys = enProps.stringPropertyNames().filter { it.startsWith("js.") }.toSet()
        val plJsKeys = plProps.stringPropertyNames().filter { it.startsWith("js.") }.toSet()

        val missingInPl = enJsKeys - plJsKeys
        val missingInEn = plJsKeys - enJsKeys

        assertTrue(missingInPl.isEmpty(), "Keys present in messages.properties but missing in messages_pl.properties: $missingInPl")
        assertTrue(missingInEn.isEmpty(), "Keys present in messages_pl.properties but missing in messages.properties: $missingInEn")

        for (sharedKey in I18nJsBundleServiceImpl.SHARED_JS_KEYS) {
            assertNotNull(enProps.getProperty(sharedKey), "Shared JS key missing in messages.properties: $sharedKey")
            assertNotNull(plProps.getProperty(sharedKey), "Shared JS key missing in messages_pl.properties: $sharedKey")
        }
    }

    @Test
    fun `placeholders match between English and Polish for formatted keys`() {
        val enMap = service.getBundleMap(english)
        val plMap = service.getBundleMap(polish)

        val placeholderRegex = Regex("""\{(\d+)\}""")

        for (key in enMap.keys) {
            val enMsg = enMap[key] ?: ""
            val plMsg = plMap[key] ?: ""

            val enPlaceholders = placeholderRegex.findAll(enMsg).map { it.groupValues[1] }.toSet()
            val plPlaceholders = placeholderRegex.findAll(plMsg).map { it.groupValues[1] }.toSet()

            assertEquals(
                enPlaceholders,
                plPlaceholders,
                "Placeholder mismatch for key '$key': EN has $enPlaceholders ('$enMsg'), PL has $plPlaceholders ('$plMsg')"
            )
        }
    }
}
