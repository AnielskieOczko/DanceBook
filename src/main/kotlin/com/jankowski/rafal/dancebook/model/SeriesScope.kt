package com.jankowski.rafal.dancebook.model

/**
 * Scope of a recurrence operation (edit or delete) on a repeating training session.
 */
enum class SeriesScope(val displayName: String) {
    THIS_EVENT("This event"),
    THIS_AND_FOLLOWING("This and following"),
    ALL_EVENTS("All events")
}
