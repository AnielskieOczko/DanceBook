package com.jankowski.rafal.dancebook.model

/**
 * How well the dancer has mastered a figure (#202). Independent of [DanceClass]: there is
 * no mapping between the two, and a figure with no level is simply unrated.
 */
enum class MedalLevel {
    BRONZE, SILVER, GOLD;

    val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
