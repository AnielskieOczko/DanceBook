package com.jankowski.rafal.dancebook.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "google.calendar")
data class GoogleCalendarProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val refreshToken: String = "",
    val calendarId: String = "",
    val timeZone: String = "Europe/Warsaw"
)
