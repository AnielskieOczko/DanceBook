package com.jankowski.rafal.dancebook.security

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class PersistentLoginRepository(
    private val jdbcTemplate: JdbcTemplate
) {

    private val rowMapper = RowMapper<PersistentLoginToken> { rs: ResultSet, _: Int ->
        PersistentLoginToken(
            series = rs.getString("series"),
            username = rs.getString("username"),
            token = rs.getString("token"),
            previousToken = rs.getString("previous_token"),
            lastUsed = rs.getTimestamp("last_used").toInstant(),
            rememberMe = rs.getBoolean("remember_me"),
            expiresAt = rs.getTimestamp("expires_at").toInstant()
        )
    }

    fun findBySeries(series: String): PersistentLoginToken? {
        val sql = "SELECT series, username, token, previous_token, last_used, remember_me, expires_at FROM persistent_logins WHERE series = ?"
        return try {
            jdbcTemplate.queryForObject(sql, rowMapper, series)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun save(token: PersistentLoginToken) {
        val sql = """
            INSERT INTO persistent_logins (series, username, token, previous_token, last_used, remember_me, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            token.series,
            token.username,
            token.token,
            token.previousToken,
            Timestamp.from(token.lastUsed),
            token.rememberMe,
            Timestamp.from(token.expiresAt)
        )
    }

    fun update(series: String, token: String, previousToken: String?, lastUsed: Instant, expiresAt: Instant) {
        val sql = "UPDATE persistent_logins SET token = ?, previous_token = ?, last_used = ?, expires_at = ? WHERE series = ?"
        jdbcTemplate.update(
            sql,
            token,
            previousToken,
            Timestamp.from(lastUsed),
            Timestamp.from(expiresAt),
            series
        )
    }

    fun deleteBySeries(series: String) {
        val sql = "DELETE FROM persistent_logins WHERE series = ?"
        jdbcTemplate.update(sql, series)
    }

    fun deleteByUsername(username: String) {
        val sql = "DELETE FROM persistent_logins WHERE username = ?"
        jdbcTemplate.update(sql, username)
    }

    fun deleteExpiredTokensForUser(username: String, now: Instant = Instant.now()): Int {
        val sql = "DELETE FROM persistent_logins WHERE username = ? AND expires_at < ?"
        return jdbcTemplate.update(sql, username, Timestamp.from(now))
    }

    fun deleteExpiredTokens(now: Instant = Instant.now()): Int {
        val sql = "DELETE FROM persistent_logins WHERE expires_at < ?"
        return jdbcTemplate.update(sql, Timestamp.from(now))
    }
}
