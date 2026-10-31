package com.jankowski.rafal.dancebook.controller.api

import com.zaxxer.hikari.HikariDataSource
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import javax.sql.DataSource
import kotlin.math.roundToLong

/**
 * Provides consolidated, human-readable metrics for JVM memory, garbage collection,
 * threads, and the database connection pool.
 *
 * Locked down to the owner/admin (Role.ADMIN).
 */
@RestController
@RequestMapping("/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
class AdminMetricsController(
    private val dataSource: DataSource
) {

    @GetMapping(produces = ["application/json"])
    fun getMetrics(): ResponseEntity<Map<String, Any>> {
        val memoryBean = ManagementFactory.getMemoryMXBean()
        val heapUsage = memoryBean.heapMemoryUsage
        val nonHeapUsage = memoryBean.nonHeapMemoryUsage

        val heapMax = if (heapUsage.max > 0) heapUsage.max else heapUsage.committed
        val heapPercentUsed = if (heapMax > 0) {
            ((heapUsage.used.toDouble() / heapMax.toDouble()) * 1000.0).roundToLong() / 10.0
        } else 0.0

        val memory = mapOf(
            "heap" to mapOf(
                "usedBytes" to heapUsage.used,
                "usedMb" to toMb(heapUsage.used),
                "committedMb" to toMb(heapUsage.committed),
                "maxMb" to toMb(heapUsage.max),
                "percentUsed" to heapPercentUsed
            ),
            "nonHeap" to mapOf(
                "usedBytes" to nonHeapUsage.used,
                "usedMb" to toMb(nonHeapUsage.used),
                "committedMb" to toMb(nonHeapUsage.committed)
            )
        )

        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gc = gcBeans.map { bean ->
            mapOf(
                "name" to bean.name,
                "collectionCount" to bean.collectionCount,
                "collectionTimeMs" to bean.collectionTime
            )
        }

        val threadBean = ManagementFactory.getThreadMXBean()
        val threads = mapOf(
            "live" to threadBean.threadCount,
            "peak" to threadBean.peakThreadCount,
            "daemon" to threadBean.daemonThreadCount,
            "totalStarted" to threadBean.totalStartedThreadCount
        )

        val dbPool = if (dataSource is HikariDataSource) {
            val pool = dataSource.hikariPoolMXBean
            mapOf(
                "activeConnections" to (pool?.activeConnections ?: 0),
                "idleConnections" to (pool?.idleConnections ?: 0),
                "totalConnections" to (pool?.totalConnections ?: 0),
                "threadsAwaitingConnection" to (pool?.threadsAwaitingConnection ?: 0),
                "maxPoolSize" to dataSource.maximumPoolSize,
                "minimumIdle" to dataSource.minimumIdle
            )
        } else {
            mapOf("status" to "HikariDataSource not in use")
        }

        val response = mapOf(
            "memory" to memory,
            "garbageCollection" to gc,
            "threads" to threads,
            "databasePool" to dbPool
        )

        return ResponseEntity.ok(response)
    }

    private fun toMb(bytes: Long): Double {
        if (bytes < 0) return -1.0
        return ((bytes.toDouble() / (1024.0 * 1024.0)) * 100.0).roundToLong() / 100.0
    }
}
