package com.jankowski.rafal.dancebook.service

/**
 * A Google Calendar operation failed.
 *
 * Carries a short, human-readable message safe to surface in the UI. The underlying
 * GoogleJsonResponseException — whose own message is a multi-kilobyte JSON document — is
 * kept as the cause so the full detail still reaches the logs.
 */
class CalendarSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
