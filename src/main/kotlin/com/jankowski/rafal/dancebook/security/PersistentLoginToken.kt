package com.jankowski.rafal.dancebook.security

import java.time.Instant

data class PersistentLoginToken(
    val series: String,
    val username: String,
    val token: String,
    val previousToken: String? = null,
    val lastUsed: Instant,
    val rememberMe: Boolean,
    val expiresAt: Instant
)
