package com.jankowski.rafal.dancebook.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DanceFigureRequestTest {

    @Test
    fun `an untouched empty Default set from the edit form is not a step set`() {
        val request = DanceFigureRequest(
            stepSets = mutableListOf(DanceFigureStepSetRequest(name = "Default", isDefault = true))
        )

        assertTrue(request.getEffectiveStepSets().isEmpty())
    }

    @Test
    fun `a Default set that has steps is kept`() {
        val set = DanceFigureStepSetRequest(
            name = "Default",
            isDefault = true,
            steps = mutableListOf(DanceFigureStepRequest(role = "LEADER", timing = "1", foot = "LF", action = "Forward"))
        )

        assertEquals(listOf(set), DanceFigureRequest(stepSets = mutableListOf(set)).getEffectiveStepSets())
    }

    @Test
    fun `a named set without steps is kept`() {
        val set = DanceFigureStepSetRequest(name = "Competition", isDefault = true)

        assertEquals(listOf(set), DanceFigureRequest(stepSets = mutableListOf(set)).getEffectiveStepSets())
    }
}
