package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.service.CalendarSyncException
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

class CalendarSyncInterceptorTest {

    private lateinit var calendarSyncService: CalendarSyncService
    private lateinit var interceptor: CalendarSyncInterceptor
    private lateinit var response: MockHttpServletResponse

    @BeforeEach
    fun setUp() {
        calendarSyncService = mock(CalendarSyncService::class.java)
        interceptor = CalendarSyncInterceptor(calendarSyncService)
        response = MockHttpServletResponse()
    }

    @Test
    fun `preHandle calls syncIfDue on GET request and returns true`() {
        val request = MockHttpServletRequest("GET", "/training-events")

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        verify(calendarSyncService).syncIfDue()
    }

    @Test
    fun `preHandle does not call syncIfDue on POST request and returns true`() {
        val request = MockHttpServletRequest("POST", "/training-events/sync")

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        verifyNoInteractions(calendarSyncService)
    }

    @Test
    fun `preHandle does not call syncIfDue on DELETE request and returns true`() {
        val request = MockHttpServletRequest("DELETE", "/training-events/123")

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        verifyNoInteractions(calendarSyncService)
    }

    @Test
    fun `preHandle swallows sync exception and still returns true so page renders`() {
        val request = MockHttpServletRequest("GET", "/training-events")
        `when`(calendarSyncService.syncIfDue()).thenThrow(CalendarSyncException("Google API 503 Outage"))

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result, "Interceptor must return true even on sync failure")
        verify(calendarSyncService).syncIfDue()
    }


    @Controller
    class DummyController {
        @GetMapping("/training-events/sample")
        fun sampleTraining(): ResponseEntity<String> = ResponseEntity.ok("training")

        @PostMapping("/training-events/sample")
        fun postTraining(): ResponseEntity<String> = ResponseEntity.ok("posted")

        @GetMapping("/dance-figures/sample")
        fun sampleNonTraining(): ResponseEntity<String> = ResponseEntity.ok("other")
    }

    @Test
    fun `interceptor only fires on GET training-events and not on non-training paths or non-GET methods`() {
        val dummyController = DummyController()
        val mockMvc = MockMvcBuilders.standaloneSetup(dummyController)
            .addMappedInterceptors(arrayOf("/training-events", "/training-events/**"), interceptor)
            .build()

        // 1. Non-training-events path: should NOT fire interceptor at all
        mockMvc.perform(MockMvcRequestBuilders.get("/dance-figures/sample"))
            .andExpect(MockMvcResultMatchers.status().isOk)
        verifyNoInteractions(calendarSyncService)

        // 2. Training-events path with POST: interceptor fires but preHandle returns without syncing
        mockMvc.perform(MockMvcRequestBuilders.post("/training-events/sample"))
            .andExpect(MockMvcResultMatchers.status().isOk)
        verifyNoInteractions(calendarSyncService)

        // 3. Training-events path with GET: interceptor fires and calls syncIfDue()
        mockMvc.perform(MockMvcRequestBuilders.get("/training-events/sample"))
            .andExpect(MockMvcResultMatchers.status().isOk)
        verify(calendarSyncService).syncIfDue()
    }
}
