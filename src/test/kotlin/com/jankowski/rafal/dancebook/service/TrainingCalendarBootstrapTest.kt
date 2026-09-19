package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class TrainingCalendarBootstrapTest {

    @Mock
    private lateinit var trainingCalendarService: TrainingCalendarService

    @Mock
    private lateinit var trainingRecordReconciliationService: TrainingRecordReconciliationService

    @InjectMocks
    private lateinit var bootstrap: TrainingCalendarBootstrap

    @Test
    fun `runs calendar bootstrap before training record reconciliation`() {
        bootstrap.run()

        val order = inOrder(trainingCalendarService, trainingRecordReconciliationService)
        order.verify(trainingCalendarService).bootstrapDefaultCalendar()
        order.verify(trainingRecordReconciliationService).reconcile()
    }
}
