package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftCodecTest {

    private val codec = AssistantDraftCodec(DraftTestSupport.mapper())

    @Test
    fun `a note request survives the round trip with its pins and attendance flag`() {
        val figure = UUID.randomUUID()
        val session = UUID.randomUUID()
        val request = MaterialRequest(
            name = "Tuesday class", description = "<div>Head drops</div>", version = 0,
            trainingEventId = session, figureIds = listOf(figure), markAttended = true
        )

        val back = codec.read(codec.toPayload(request), MaterialRequest::class.java)

        assertEquals(request, back)
    }

    @Test
    fun `a session request survives the round trip, dates and all`() {
        val request = TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0), segments = mutableListOf(TrainingEventSegmentRequest(UUID.randomUUID(), 60))
        )

        val payload = codec.toPayload(request)
        assertEquals("2026-10-03", payload["date"], "dates are stored as ISO strings, readable in the jsonb column")
        assertEquals(request, codec.read(payload, TrainingEventRequest::class.java))
    }

    @Test
    fun `a figure request survives the round trip`() {
        val request = DanceFigureRequest(name = "Heel Turn", danceTypeId = UUID.randomUUID(), danceClass = DanceClass.D, alternativeTiming = "1 2 3")

        assertEquals(request, codec.read(codec.toPayload(request), DanceFigureRequest::class.java))
    }

    @Test
    fun `a figure draft reads back whatever order the database returns its keys in`() {
        // Postgres jsonb reorders object keys (shorter first, then alphabetical), so a computed property can arrive first.
        val request = DanceFigureRequest(name = "Heel Turn", danceTypeId = UUID.randomUUID())
        val inDbOrder = LinkedHashMap<String, Any?>()
        inDbOrder["effectiveStepSets"] = emptyList<Any>()
        inDbOrder.putAll(codec.toPayload(request).filterKeys { it != "effectiveStepSets" })

        assertEquals(request, codec.read(inDbOrder, DanceFigureRequest::class.java))
    }
}
