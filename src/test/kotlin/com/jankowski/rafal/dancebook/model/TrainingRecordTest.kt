package com.jankowski.rafal.dancebook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class TrainingRecordTest {

    @Test
    fun `only attended and skipped map to a confirmed outcome`() {
        assertEquals(TrainingOutcome.ATTENDED, TrainingOutcome.from(AttendanceStatus.ATTENDED))
        assertEquals(TrainingOutcome.SKIPPED, TrainingOutcome.from(AttendanceStatus.SKIPPED))
        assertNull(TrainingOutcome.from(AttendanceStatus.PLANNED))
        assertNull(TrainingOutcome.from(AttendanceStatus.CANCELLED))
    }

    @Test
    fun `a record is orphaned once it has been stamped`() {
        val record = TrainingRecord()

        assertFalse(record.isOrphaned)

        record.orphanedAt = LocalDateTime.now()
        assertTrue(record.isOrphaned)
    }

    @Test
    fun `a segment reads by its live category, and by its snapshot once that is gone`() {
        val category = DanceCategory().apply {
            id = UUID.randomUUID()
            name = "Standard"
        }
        val segment = TrainingRecordSegment().apply {
            danceCategory = category
            categoryName = "Standard"
            durationMinutes = 60
        }

        // A renamed category relabels the history that still points at it: same style, new name.
        category.name = "Ballroom"
        assertEquals("Ballroom", segment.label)

        // A deleted category leaves the link null, and the snapshot is what is left to read.
        segment.danceCategory = null
        assertEquals("Standard", segment.label)
    }
}
