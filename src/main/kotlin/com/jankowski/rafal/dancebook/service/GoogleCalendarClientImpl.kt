package com.jankowski.rafal.dancebook.service

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import com.jankowski.rafal.dancebook.model.TrainingEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

@Service
class GoogleCalendarClientImpl(
    private val calendarProperties: GoogleCalendarProperties
) : GoogleCalendarClient {

    private val logger = LoggerFactory.getLogger(GoogleCalendarClientImpl::class.java)

    // Lazy on purpose, mirroring GoogleDriveService: a missing env var must fail on first
    // use, not at application startup.
    private val credentials by lazy {
        logger.info("Initializing Google Calendar credentials...")

        val clientId = calendarProperties.clientId.trim()
        val clientSecret = calendarProperties.clientSecret.trim()
        val refreshToken = calendarProperties.refreshToken.trim()

        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank() || calendarId.isBlank()) {
            val missing = mutableListOf<String>()
            if (clientId.isBlank()) missing.add("GOOGLE_CLIENT_ID")
            if (clientSecret.isBlank()) missing.add("GOOGLE_CLIENT_SECRET")
            if (refreshToken.isBlank()) missing.add("GOOGLE_CALENDAR_REFRESH_TOKEN")
            if (calendarId.isBlank()) missing.add("GOOGLE_CALENDAR_ID")

            val errorMsg = "CRITICAL: Missing required Google Calendar properties: ${missing.joinToString()}. " +
                           "Check your Environment Variables / GitHub Secrets!"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(refreshToken)
            .build()
    }

    private val calendar: Calendar by lazy {
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        Calendar.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName("DanceBook")
            .build()
    }

    private val calendarId: String
        get() = calendarProperties.calendarId.trim()

    private val zone: ZoneId
        get() = ZoneId.of(calendarProperties.timeZone)

    override fun createEvent(event: TrainingEvent): String = translating("create") {
        val created = calendar.events().insert(calendarId, event.toGoogleEvent()).execute()
        logger.info("Created calendar event {} for training event '{}'", created.id, event.title)
        created.id
    }

    override fun updateEvent(googleEventId: String, event: TrainingEvent) = translating("update") {
        calendar.events().update(calendarId, googleEventId, event.toGoogleEvent()).execute()
        logger.info("Updated calendar event {} for training event '{}'", googleEventId, event.title)
    }

    override fun deleteEvent(googleEventId: String) {
        try {
            calendar.events().delete(calendarId, googleEventId).execute()
            logger.info("Deleted calendar event {}", googleEventId)
        } catch (e: GoogleJsonResponseException) {
            // Already gone is the outcome we wanted; anything else is a real failure the
            // service layer needs to see.
            if (e.statusCode == 404 || e.statusCode == 410) {
                logger.info("Calendar event {} was already deleted ({}), treating as success", googleEventId, e.statusCode)
                return
            }
            throw asSyncException("delete", e)
        }
    }

    private fun <T> translating(action: String, block: () -> T): T =
        try {
            block()
        } catch (e: GoogleJsonResponseException) {
            throw asSyncException(action, e)
        }

    /**
     * GoogleJsonResponseException.message is the entire JSON error document, which is
     * useless in a form field. Pull out the one-line reason and keep the rest in the log.
     */
    private fun asSyncException(action: String, e: GoogleJsonResponseException): CalendarSyncException {
        val detail = e.details?.message ?: e.statusMessage ?: "unknown error"
        logger.error("Calendar {} failed with {} {}", action, e.statusCode, detail, e)
        return CalendarSyncException("Google Calendar $action failed (${e.statusCode}): $detail", e)
    }

    private fun TrainingEvent.toGoogleEvent(): Event = Event().apply {
        summary = title
        description = buildCalendarDescription(this@toGoogleEvent)
        start = toEventDateTime(startTime)
        end = toEventDateTime(endTime)
    }

    /**
     * The app stores LocalDateTime everywhere; the configured app zone is applied only
     * here, at the API boundary.
     */
    private fun toEventDateTime(local: LocalDateTime): EventDateTime =
        EventDateTime()
            .setDateTime(DateTime(Date.from(local.atZone(zone).toInstant())))
            .setTimeZone(calendarProperties.timeZone)

    /** Google Calendar has no field for our metadata, so fold the useful parts into the body. */
    private fun buildCalendarDescription(event: TrainingEvent): String {
        val lines = mutableListOf<String>()
        event.description?.takeIf { it.isNotBlank() }?.let { lines.add(it) }
        lines.add("Type: ${event.eventType}")
        if (event.segments.isNotEmpty()) {
            val styles = event.segments.joinToString(", ") { "${it.danceCategory?.name} ${it.durationMinutes}min" }
            lines.add("Styles: $styles")
        }
        event.materialsUrl?.takeIf { it.isNotBlank() }?.let { lines.add("Materials: $it") }
        return lines.joinToString("\n")
    }
}
