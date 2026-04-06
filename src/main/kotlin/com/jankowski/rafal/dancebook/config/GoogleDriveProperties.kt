package com.jankowski.rafal.dancebook.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "google.drive")
data class GoogleDriveProperties(
    val clientId: String,
    val folderId: String
)
