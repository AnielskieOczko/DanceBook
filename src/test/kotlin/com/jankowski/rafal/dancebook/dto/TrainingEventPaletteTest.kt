package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class TrainingEventPaletteTest {

    @Test
    fun `all status swatches yield CSS custom property tokens and no hex literals`() {
        val swatches = listOf(
            TrainingEventPalette.PLANNED,
            TrainingEventPalette.UNCONFIRMED,
            TrainingEventPalette.ATTENDED,
            TrainingEventPalette.SKIPPED,
            TrainingEventPalette.CANCELLED
        )

        assertEquals(5, swatches.map { it.key }.distinct().size)
        assertEquals(5, swatches.map { it.color }.distinct().size)

        swatches.forEach { swatch ->
            assertTrue(swatch.color.startsWith("var(--color-"), "color should be a CSS token: ${swatch.color}")
            assertFalse(swatch.color.contains("#"), "color must not contain a hex literal: ${swatch.color}")
            assertEquals("color-mix(in srgb, ${swatch.color} 10%, transparent)", swatch.tint)
        }
    }

    @Test
    fun `status mapping preserves domain distinction`() {
        val now = LocalDateTime.now()
        val user = AppUser().apply { id = UUID.randomUUID() }

        val planned = TrainingEvent().apply {
            startTime = now.plusDays(1)
            endTime = now.plusDays(1).plusHours(1)
            setAttendance(user, AttendanceStatus.PLANNED)
        }
        assertEquals(TrainingEventPalette.PLANNED, TrainingEventPalette.swatchFor(planned, user))

        val awaiting = TrainingEvent().apply {
            startTime = now.minusDays(1)
            endTime = now.minusDays(1).plusHours(1)
            setAttendance(user, AttendanceStatus.PLANNED)
        }
        assertEquals(TrainingEventPalette.UNCONFIRMED, TrainingEventPalette.swatchFor(awaiting, user))

        val attended = TrainingEvent().apply {
            startTime = now.minusDays(1)
            endTime = now.minusDays(1).plusHours(1)
            setAttendance(user, AttendanceStatus.ATTENDED)
        }
        assertEquals(TrainingEventPalette.ATTENDED, TrainingEventPalette.swatchFor(attended, user))

        val skipped = TrainingEvent().apply {
            startTime = now.minusDays(1)
            endTime = now.minusDays(1).plusHours(1)
            setAttendance(user, AttendanceStatus.SKIPPED)
        }
        assertEquals(TrainingEventPalette.SKIPPED, TrainingEventPalette.swatchFor(skipped, user))

        val cancelled = TrainingEvent().apply {
            startTime = now.plusDays(1)
            endTime = now.plusDays(1).plusHours(1)
            setAttendance(user, AttendanceStatus.CANCELLED)
        }
        assertEquals(TrainingEventPalette.CANCELLED, TrainingEventPalette.swatchFor(cancelled, user))
    }

    @Test
    fun `outcome swatch mapping produces attended and skipped swatches`() {
        assertEquals(TrainingEventPalette.ATTENDED, TrainingEventPalette.swatchFor(TrainingOutcome.ATTENDED))
        assertEquals(TrainingEventPalette.SKIPPED, TrainingEventPalette.swatchFor(TrainingOutcome.SKIPPED))
    }

    @Test
    fun `chart colors cycle through eight distinct chart tokens`() {
        val colors = (0 until 8).map { TrainingEventPalette.chartColor(it) }
        assertEquals(8, colors.distinct().size)

        colors.forEachIndexed { index, color ->
            assertEquals("var(--color-chart-${index + 1})", color)
            assertFalse(color.contains("#"), "chart color must not be a hex literal: $color")
        }

        // Check modulo wrap-around
        assertEquals(colors[0], TrainingEventPalette.chartColor(8))
        assertEquals(colors[1], TrainingEventPalette.chartColor(9))
    }

    @Test
    fun `legend contains all five swatches in lifecycle order`() {
        assertEquals(
            listOf(
                TrainingEventPalette.PLANNED,
                TrainingEventPalette.UNCONFIRMED,
                TrainingEventPalette.ATTENDED,
                TrainingEventPalette.SKIPPED,
                TrainingEventPalette.CANCELLED
            ),
            TrainingEventPalette.LEGEND
        )
    }

    @Test
    fun `text and grid colors are CSS variable tokens`() {
        assertEquals("var(--color-on-surface)", TrainingEventPalette.TEXT_COLOR)
        assertEquals("var(--color-outline-variant)", TrainingEventPalette.UNASSIGNED_COLOR)
        assertEquals("var(--color-outline-variant)", TrainingEventPalette.CHART_GRID_COLOR)
    }
}
