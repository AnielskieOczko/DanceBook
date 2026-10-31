package com.jankowski.rafal.dancebook.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LoggingInitializationContext
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("prod")
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["google.calendar.calendar-id=test-cal"])
class ProductionProfileLoggingTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )

        private val objectMapper = ObjectMapper()
    }

    @Autowired
    private lateinit var environment: Environment

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var calendarClient: GoogleCalendarClient

    @BeforeEach
    fun setUpLogging() {
        System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT")
        System.clearProperty("logging.structured.format.console")
        val loggingSystem = LoggingSystem.get(javaClass.classLoader)
        loggingSystem.cleanUp()
        loggingSystem.beforeInitialize()
        loggingSystem.initialize(LoggingInitializationContext(environment as ConfigurableEnvironment), null, null)
    }

    @AfterEach
    fun tearDownLogging() {
        System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT")
        System.clearProperty("logging.structured.format.console")
        val loggingSystem = LoggingSystem.get(javaClass.classLoader)
        loggingSystem.cleanUp()
        loggingSystem.beforeInitialize()
    }

    @Test
    fun `logged exception appears as exactly one JSON line containing severity message and full stack trace`(output: CapturedOutput) {
        val logger = LoggerFactory.getLogger("com.jankowski.rafal.dancebook.test.ProdLogger")
        val marker = "PROD_UNIQUE_ERROR_TEST_MARKER"

        logger.error("$marker: operation failed", IllegalStateException("Root failure in test", RuntimeException("Nested failure")))

        val lines = output.out.lines().filter { it.contains(marker) }
        assertEquals(1, lines.size, "Logged exception must appear as exactly one line in console output, was: ${output.out}")

        val jsonLine = lines.first()
        val rootNode = objectMapper.readTree(jsonLine)

        assertEquals("ERROR", rootNode["severity"].asText())
        assertNotNull(rootNode["timestamp"])
        assertEquals("com.jankowski.rafal.dancebook.test.ProdLogger", rootNode["logger"].asText())

        val message = rootNode["message"].asText()
        assertTrue(message.contains(marker))
        assertTrue(message.contains("IllegalStateException: Root failure in test"))
        assertTrue(message.contains("Caused by: java.lang.RuntimeException: Nested failure"))

        val stackTrace = rootNode["stack_trace"].asText()
        assertTrue(stackTrace.contains("IllegalStateException: Root failure in test"))
        assertTrue(stackTrace.contains("Caused by: java.lang.RuntimeException: Nested failure"))
    }
}

@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["google.calendar.calendar-id=test-cal"])
class DefaultProfileLoggingTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired
    private lateinit var environment: Environment

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var calendarClient: GoogleCalendarClient

    @BeforeEach
    fun setUpLogging() {
        System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT")
        System.clearProperty("logging.structured.format.console")
        val loggingSystem = LoggingSystem.get(javaClass.classLoader)
        loggingSystem.cleanUp()
        loggingSystem.beforeInitialize()
        loggingSystem.initialize(LoggingInitializationContext(environment as ConfigurableEnvironment), null, null)
    }

    @AfterEach
    fun tearDownLogging() {
        System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT")
        System.clearProperty("logging.structured.format.console")
        val loggingSystem = LoggingSystem.get(javaClass.classLoader)
        loggingSystem.cleanUp()
        loggingSystem.beforeInitialize()
    }

    @Test
    fun `without production profile log output remains plain text`(output: CapturedOutput) {
        val logger = LoggerFactory.getLogger("com.jankowski.rafal.dancebook.test.DevLogger")
        val marker = "DEV_UNIQUE_PLAIN_TEXT_TEST_MARKER"

        logger.error("$marker: plain error happened", IllegalArgumentException("Invalid test argument"))

        val lines = output.out.lines().filter { it.contains(marker) }
        assertTrue(lines.isNotEmpty(), "Output must contain the logged message")

        // The log line must NOT be formatted as JSON
        val logLine = lines.first()
        assertFalse(logLine.trim().startsWith("{"), "Default profile logs must be plain text, not JSON: $logLine")
    }
}
