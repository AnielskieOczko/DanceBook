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
import java.time.Instant
import java.time.LocalDate
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

        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            val missing = mutableListOf<String>()
            if (clientId.isBlank()) missing.add("GOOGLE_CLIENT_ID")
            if (clientSecret.isBlank()) missing.add("GOOGLE_CLIENT_SECRET")
            if (refreshToken.isBlank()) missing.add("GOOGLE_CALENDAR_REFRESH_TOKEN")

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

    private val zone: ZoneId
        get() = ZoneId.of(calendarProperties.timeZone)

    override fun createEvent(calendarId: String, event: TrainingEvent): String {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
        return translating("create") {
            val created = calendar.events().insert(calendarId, event.toGoogleEvent()).execute()
            logger.info("Created calendar event {} for training event '{}'", created.id, event.title)
            created.id
        }
    }

    override fun updateEvent(calendarId: String, googleEventId: String, event: TrainingEvent) {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
        translating("update") {
            calendar.events().update(calendarId, googleEventId, event.toGoogleEvent()).execute()
            logger.info("Updated calendar event {} for training event '{}'", googleEventId, event.title)
        }
    }

    override fun deleteEvent(calendarId: String, googleEventId: String) {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
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

    override fun verifyCalendar(calendarId: String): String {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
        return translating("verify") {
            val summary = calendar.calendars().get(calendarId).execute().summary
            logger.info("Verified calendar {} ('{}')", calendarId, summary)
            summary ?: calendarId
        }
    }

    override fun listChanges(calendarId: String, syncToken: String?): CalendarChangeSet {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
        return try {
            val changes = mutableListOf<CalendarChange>()
            var pageToken: String? = null
            var nextSyncToken: String? = null
            val isFullSync = syncToken.isNullOrBlank()
            val windowStart = if (isFullSync) LocalDateTime.now().minusYears(1) else null

            var drainCompleted = false
            do {
                val listRequest = calendar.events().list(calendarId).apply {
                    singleEvents = true
                    if (isFullSync) {
                        timeMin = DateTime(Date.from(windowStart!!.atZone(zone).toInstant()))
                    } else {
                        this.syncToken = syncToken
                    }
                    if (pageToken != null) {
                        this.pageToken = pageToken
                    }
                }
                val response = listRequest.execute()
                response.items?.forEach { event ->
                    toCalendarChange(event)?.let { changes.add(it) }
                }
                pageToken = response.nextPageToken
                nextSyncToken = response.nextSyncToken
                if (pageToken == null && !nextSyncToken.isNullOrBlank()) {
                    drainCompleted = true
                }
            } while (pageToken != null)

            val isCompleteWindow = isWindowComplete(isFullSync, drainCompleted)

            CalendarChangeSet(
                changes = changes,
                nextSyncToken = nextSyncToken,
                fullResyncRequired = false,
                isCompleteWindow = isCompleteWindow,
                windowStart = windowStart
            )
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 410) {
                logger.info("Calendar {} sync token expired (410 Gone), full resync required", calendarId)
                CalendarChangeSet(
                    changes = emptyList(),
                    nextSyncToken = null,
                    fullResyncRequired = true,
                    isCompleteWindow = false,
                    windowStart = null
                )
            } else {
                throw asSyncException("list changes for", e)
            }
        }
    }

    internal fun isWindowComplete(isFullSync: Boolean, drainCompleted: Boolean): Boolean =
        isFullSync && drainCompleted

    internal fun toCalendarChange(event: Event): CalendarChange? {
        val eventId = event.id ?: return null

        if (event.status == "cancelled") {
            return CalendarChange.Cancelled(eventId)
        }

        val start = parseEventDateTime(event.start) ?: return null
        val end = parseEventDateTime(event.end) ?: start.plusHours(1)
        val adjustedEnd = if (!end.isAfter(start)) {
            if (event.start?.dateTime == null) start.plusDays(1) else start.plusHours(1)
        } else {
            end
        }

        return CalendarChange.Upserted(
            googleEventId = eventId,
            title = event.summary ?: "",
            start = start,
            end = adjustedEnd,
            description = event.description
        )
    }

    private fun parseEventDateTime(eventDateTime: EventDateTime?): LocalDateTime? {
        if (eventDateTime == null) return null
        if (eventDateTime.dateTime != null) {
            return Instant.ofEpochMilli(eventDateTime.dateTime.value)
                .atZone(zone)
                .toLocalDateTime()
        }
        if (eventDateTime.date != null) {
            val dateStr = eventDateTime.date.toStringRfc3339().substring(0, 10)
            return LocalDate.parse(dateStr).atStartOfDay()
        }
        return null
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
        val detail = when (e.statusCode) {
            404 -> "no such calendar"
            403 -> "not shared with this app's Google account"
            else -> e.details?.message ?: e.statusMessage ?: "unknown error"
        }
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
        event.material?.name?.takeIf { it.isNotBlank() }?.let { lines.add("Note: $it") }
        event.materialsUrl?.takeIf { it.isNotBlank() }?.let { lines.add("Materials: $it") }
        return lines.joinToString("\n")
    }
}
