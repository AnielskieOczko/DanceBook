package com.jankowski.rafal.dancebook.dto

import java.time.LocalDateTime

/**
 * View model for an activity item on the profile page.
 *
 * The icon name and action text are computed in Kotlin so Thymeleaf receives
 * plain strings, avoiding complex expressions inside fragment selectors.
 */
data class ProfileActivityItem(
    val icon: String,
    val actionText: String,
    val targetName: String?,
    val dateLabel: String,
    val createdAt: LocalDateTime
)
